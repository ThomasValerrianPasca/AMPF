package net.floodlightcontroller.cost;

import java.util.*;
import java.util.Map.Entry;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.Future;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.TimerTask;
import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFStatisticsReply;
import org.openflow.protocol.OFStatisticsRequest;
import org.openflow.protocol.OFType;
import org.openflow.protocol.factory.*;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.util.U16;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openflow.protocol.statistics.*;
import org.python.antlr.ast.Tuple;
import org.jboss.netty.buffer.ChannelBuffer;
import com.sun.xml.internal.ws.api.message.Packet;

import sun.security.provider.certpath.Vertex;
import net.floodlightcontroller.learningswitch.*;
import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IListener.Command;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.web.AllSwitchStatisticsResource;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.linkdiscovery.LinkInfo;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.ICMP;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.routing.ForwardingBase;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.util.OFMessageDamper;


/**
 * Module to perform round-robin load balancing.
 * 
 */
public class Cost implements IOFMessageListener, IFloodlightModule ,ICost{
	boolean flag=true;
	DemoTimerTask timerTask;
	long last_update=10;
	long current_update;
	protected ReentrantReadWriteLock lock;
	//FloodlightContext cnttx;
	int counter;
	//public Map<IOFSwitch ,Long>switch_latency_recv=new HashMap<IOFSwitch,Long>();
	Map<Link,Long>lat_final=new HashMap<Link,Long>();
	// Interface to Floodlight core for interacting with connected switches
	protected IFloodlightProviderService floodlightProvider;
	
	// Interface to link discovery service
	protected ILinkDiscoveryService linkDiscoveryProvider;
	
	// Interface to device manager service
	protected IDeviceService deviceProvider;
	
	// Interface to the logging system
	protected static Logger logger;
	
    // flow-mod - for use in the cookie
    public static final int Cost_COOKIE = 20;
	
	protected static int OFMESSAGE_DAMPER_CAPACITY = 10000; // ms.
    protected static int OFMESSAGE_DAMPER_TIMEOUT = 25000; // ms 
    protected OFMessageDamper messageDamper;
    
	// TODO Create list of servers to which traffic should be balanced
	java.util.Date date= new java.util.Date();
	
	/**
	 * Provides an identifier for our OFMessage listener.
	 * Important to override!
	 * */
	@Override
	public String getName() {
		return Cost.class.getSimpleName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// Auto-generated method stub
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// Auto-generated method stub
		return false;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l = 
                new ArrayList<Class<? extends IFloodlightService>>();
        l.add(ICost.class);
        return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		 Map<Class<? extends IFloodlightService>,
         IFloodlightService> m = 
             new HashMap<Class<? extends IFloodlightService>,
                 IFloodlightService>();
		 	m.put(ICost.class, this);
     return m;
	}

	/**
	 * Tells the module loading system which modules we depend on.
	 * Important to override! 
	 */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService >> floodlightService = 
			new ArrayList<Class<? extends IFloodlightService>>();
		floodlightService.add(IFloodlightProviderService.class);
		floodlightService.add(ILinkDiscoveryService.class);
		floodlightService.add(IDeviceService.class);
		floodlightService.add(ICost.class);
		return floodlightService;
	}

	/**
	 * Loads dependencies and initializes data structures.
	 * Important to override! 
	 */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		linkDiscoveryProvider = context.getServiceImpl(ILinkDiscoveryService.class);
		deviceProvider = context.getServiceImpl(IDeviceService.class);
		logger = LoggerFactory.getLogger(Cost.class);
	//	messageDamper = new OFMessageDamper(OFMESSAGE_DAMPER_CAPACITY,EnumSet.of(OFType.FLOW_MOD),OFMESSAGE_DAMPER_TIMEOUT);
		
	}

	/**
	 * Tells the Floodlight core we are interested in PACKET_IN messages.
	 * Important to override! 
	 * */
	@Override
	public void startUp(FloodlightModuleContext context) {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		floodlightProvider.addOFMessageListener(OFType.ERROR, this);
		floodlightProvider.addOFMessageListener(OFType.ECHO_REPLY, this);
		floodlightProvider.addOFMessageListener(OFType.BARRIER_REQUEST, this);
		floodlightProvider.addOFMessageListener(OFType.ECHO_REQUEST, this);
		floodlightProvider.addOFMessageListener(OFType.HELLO, this);
		IOFMessageListener listener = this;
		 timerTask = new DemoTimerTask(floodlightProvider,this);
		////System.out.println("Before starting thread");
		timerTask.start();
		
		
	//	Interswitchdelay interswitchdelay=new Interswitchdelay(floodlightProvider,this,linkDiscoveryProvider,messageDamper);
	//	interswitchdelay.start();
		/*StaticTopology st=new StaticTopology();
		st.gettopology();
		st.printtopology();*/
	//	new Timer().schedule(timerTask, (long)100);
		
	}
	
	

	public void get_stats()
	{
		long last_time_stamp=0;
		java.util.Date date= new java.util.Date();
		//LearningSwitchTable l;
		
		
	/*	while(true)
		{
			long present_time_stamp = System.currentTimeMillis();
			if(present_time_stamp-last_time_stamp > 2)
			{
			//	macVlanToSwitchPortMap
			}
				
		}*/
	}
	
	
	
	public int byteArrayToInt(byte[] b) 
	{
	    int value = 0;
	    for (int i = 0; i < 4; i++) {
	        int shift = (4 - 1 - i) * 8;
	        value += (b[i] & 0x000000FF) << shift;
	    }
	    return value;
	}
	/**
	 * Receives an OpenFlow message from the Floodlight core and initiates the appropriate control logic.
	 * Important to override!
	 */
	@Override
	public net.floodlightcontroller.core.IListener.Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		////System.out.println("In receive ");
		Long Recv_time;
		Recv_time=System.currentTimeMillis();
		Map<IOFSwitch, Long> switch_delay;
		Long temp=null;
		IOFSwitch temp_sw=null;
		//we care about statistics reply
		////System.out.println("Msg type is "+ msg.getType());
		if(msg.getType()==OFType.STATS_REPLY){
			
			OFStatisticsReply r=(OFStatisticsReply) msg;
		//==	//System.out.println("Stats*************");
			if(r.getStatisticType()==OFStatisticsType.FLOW) {
	            for(OFStatistics stat: r.getStatistics()) {
	                OFFlowStatisticsReply flowStatReply = (OFFlowStatisticsReply)stat;
	            //==    //System.out.println("Flow stats"+flowStatReply.toString());
	              
	                    }
	                }
	            
			
			
			
			switch_delay=timerTask.get_map();
			for (Map.Entry<IOFSwitch,Long> entry : switch_delay.entrySet()) {
		//		//==//System.out.println("modify");
				if(entry.getKey().equals(sw))
				{
					temp_sw=sw;
					temp=entry.getValue();
					temp=Recv_time-temp;
					
					////System.out.print("Entry Switch "+entry.getKey().getId()+" : ");
					//==//System.out.println("-------------"+temp+" nanoseconds");
				
				////==//System.out.println(entry.getValue());
				}
				
			}
			//printSwitches();
	        //printLinks();
			switch_delay.put(temp_sw, temp);
			counter++;
			current_update=System.currentTimeMillis();
			if(current_update-last_update>2)
			{
				findlatency();
				last_update=System.currentTimeMillis();
			}
			
			//==//System.out.println("Switch delay size"+switch_delay.size());
			
		//	switch_latency_recv.put(sw, System.currentTimeMillis());
			
		//	//==//System.out.println("##################################################");
			
			////==//System.out.println(msg);
			
			return Command.CONTINUE;
			
		}
		
		// We only care about packet-in messages
		
		else if (msg.getType() != OFType.PACKET_IN) { 
			// Allow the next module to also process this OpenFlow message
			
		////System.out.println("very new");
		//	//==//System.out.println(msg.getDataAsString(temp_sw, msg, cntx));
			////==//System.out.println(msg.toString());
		    return Command.CONTINUE;
		}
		
		////System.out.println("After conditionla blocks");
		OFPacketIn pi = (OFPacketIn)msg;
				
		// Parse the received packet	
		//==//System.out.println("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@");
		//==//System.out.println(pi);
        OFMatch match = new OFMatch();
        match.loadFromPacket(pi.getPacketData(), pi.getInPort());
        //==//System.out.println("Switch ID "+sw.getStringId());
        //==//System.out.println("source mac"+byteArrayToInt(match.getDataLayerSource()));
        //==//System.out.println("Destination mac"+byteArrayToInt(match.getDataLayerDestination()));
        //==//System.out.println("Source IP"+match.getNetworkSource());
        //==//System.out.println("Destination IP"+match.getNetworkDestination());
        //==//System.out.println("Data Layer Type "+match.getDataLayerType());
        
        
        //handleARPRequest(sw,cntx);
		// We only care about IP packets
		if (match.getDataLayerType() != Ethernet.TYPE_IPv4) {
			
			//==//System.out.println("GOT IIIIIIITTTTTTTTTTTTTTTTTttttttttttt");
			//==//System.out.println("Input Port"+pi.getInPort());
			//==//System.out.println("Reason"+pi.getReason().name());
			//==//System.out.println("Layer tyype"+match.getDataLayerType());
			
			String data=pi.getPacketData().toString();
			//==//System.out.println("data"+data);
			//==//System.out.println(pi.getBufferId());
			pi.getLength();
			pi.toString();
			if(flag)
			{
			     printLinks();
			//ForwardingBase b = new ForwardingBase();
			short outport=(short)2;
		//	pushPacket(sw, match, pi, outport, cntx);
		//	//==//System.out.println("push packetout");
			//flag=true;
			}

		    return Command.CONTINUE;
		}
		
		logger.debug("Received an IPv4 packet");
	
		//printLinks();
		/*byte[] temp1;
		temp1=pi.getPacketData();
		//==//System.out.println("Serialized Received"+temp1);*/
		return Command.CONTINUE;
    }
	
	
	
	public List<OFStatistics> statsforport(long switchId )
	{
		IOFSwitch sw = floodlightProvider.getSwitches().get(switchId);
        Future<List<OFStatistics>> future;
        OFStatisticsRequest req = new OFStatisticsRequest();
        req.setStatisticType(OFStatisticsType.PORT);
        int requestLength = req.getLengthU();
        List<OFStatistics> values = null;
		OFPortStatisticsRequest specificReq = new OFPortStatisticsRequest();
        specificReq.setPortNumber((short)OFPort.OFPP_NONE.getValue());
        req.setStatistics(Collections.singletonList((OFStatistics)specificReq));
        requestLength += specificReq.getLength();
        req.setLengthU(requestLength);
        try {
            future = sw.getStatistics(req);
            values = future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            //System.out.println("Failure retrieving statistics from switch " + sw+"Error"+ e);
        }
     
    return values;
	}
	 
    private void findlatency() {
    	//==//System.out.println("Finding latency");
		long cntlr_to_sw1=0,cntlr_to_sw2=0,sw1_to_sw2=0;
		Map<Link,LinkInfo> links_temp = linkDiscoveryProvider.getLinks();
		
    	lat_final=linkDiscoveryProvider.getLatency();
    	Map<IOFSwitch, Long> switch_delay_final=timerTask.get_map();
    	Iterator<Entry<Link, Long>> it=lat_final.entrySet().iterator();

    	
    	
	    	while(it.hasNext())
			{
	    		//==//System.out.println("In while loop");
			Entry<Link,Long> o=(Entry<Link, Long>) it.next();
			
			if(links_temp.containsKey(o.getKey()))
			{
				//==//System.out.println("Found the key");
			for (Map.Entry<IOFSwitch,Long> entry : switch_delay_final.entrySet()) {
				//		//==//System.out.println("modify");
				////==//System.out.println(
						if(entry.getKey().getId()==o.getKey().getSrc())
						{						
							cntlr_to_sw1=entry.getValue();
						//==	//System.out.print("Switch "+entry.getKey().getId()+" : ");
							//==//System.out.println("-------------@"+cntlr_to_sw1+" nanoseconds");
						}
						if(entry.getKey().getId()==o.getKey().getDst())
						{						
							cntlr_to_sw2=entry.getValue();
					//==		//System.out.print("Switch "+entry.getKey().getId()+" : ");
							//==//System.out.println("-------------@"+cntlr_to_sw2+" nanoseconds");
						}
						
					}
			////==//System.out.println("!!!!Source Switch "+o.getKey().getSrc()+", Source Port "+o.getKey().getSrc()+", Destination switch "+o.getKey().getDst()+", Destination Port "+o.getKey().getDstPort());
			if(o.getValue().intValue()>0 && cntlr_to_sw1<10000 && cntlr_to_sw2<10000)
			{
			//==//System.out.println("$$$$$$$$$## value"+o.getValue());
			
			sw1_to_sw2=o.getValue()-(cntlr_to_sw1/2)-(cntlr_to_sw2/2);
			//==//System.out.println("!!!!Source Switch "+o.getKey().getSrc()+", Source Port "+o.getKey().getSrcPort()+", Destination switch "+o.getKey().getDst()+", Destination Port "+o.getKey().getDstPort());
			//==//System.out.println("Latency "+sw1_to_sw2);
			//lat_final.put(o.getKey(), sw1_to_sw2);
			
				////==//System.out.println("SrcSwitch="+lin.getSrc()+" SrcPort="+lin.getSrcPort()
					//	+", DstSwitch="+lin.getDst()+", DstPort="+lin.getDstPort());
			}	
		}
		}
	    	
	    	//==//System.out.println("$$LLLLLLLinks");
			
    	printLinks();

	}
    
	public Map<Link, Long> getlinkandcost(){
		return lat_final;
	}

	public void pushPacket(IOFSwitch sw, OFMatch match, OFPacketIn pi, 
                           short outport, FloodlightContext cntx) {
    	short rand_port=0;
       
		if (pi == null) {
            return;
        } else if (pi.getInPort() == outport){
           //==////System.out.println("Packet out not sent as the outport matches inport. {}"+pi);
           
          /* short i=0;
           while(rand_port==pi.getInPort() | rand_port==0)
           {
        	   rand_port=(short) (i%4);
        	   i++;
           }
           */
            return;
        }
		//outport=rand_port;
		
		
        // The assumption here is (sw) is the switch that generated the 
        // packet-in. If the input port is the same as output port, then
        // the packet-out should be ignored.
        if (pi.getInPort() == outport) {
           
        	 System.out.println("Attempting to do packet-out to the same " + 
                          "interface as packet-in. Dropping packet. " + 
                          " SrcSwitch={}, match = {}, pi={}"+ 
                          new Object[]{sw, match, pi});
                return;
            
        }
        	byte[] pkt=createpacket(sw, outport);
       
        	//==//System.out.println("PacketOut srcSwitch={} match={} pi={}"+ new Object[] {sw, match, pi});
       

        OFPacketOut po =
                (OFPacketOut) floodlightProvider.getOFMessageFactory()
                                                .getMessage(OFType.PACKET_OUT);

        
        OFMatch m= new OFMatch();
        //m.loadFromPacket(pi.getPacketData(),(short)0);
        int Rand_src_IP = IPv4.toIPv4Address("10.0.0.6");
        m.setNetworkSource(Rand_src_IP);
        
        // set actions
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(outport, (short) 0xffff));
        
        po.setActions(actions)
          .setActionsLength((short) OFActionOutput.MINIMUM_LENGTH);
        short poLength =
                (short) (po.getActionsLength() + OFPacketOut.MINIMUM_LENGTH);
        
        // If the switch doens't support buffering set the buffer id to be none
        // otherwise it'll be the the buffer id of the PacketIn
        if (sw.getBuffers() == 0) {
            // We set the PI buffer id here so we don't have to check again below
            pi.setBufferId(OFPacketOut.BUFFER_ID_NONE);
            po.setBufferId(OFPacketOut.BUFFER_ID_NONE);
        } else {
            po.setBufferId(pi.getBufferId());
        }
        
        po.setInPort(pi.getInPort());
        po.setInPort(OFPort.OFPP_NONE);
        pi.setBufferId(0xffffffff);
        //==//System.out.println("Context Information"+cntx.getClass().getName());
     //  IPv4Packet p= new IPv4Packet();
        // If the buffer id is none or the switch doesn's support buffering
        // we send the data with the packet out
        if (pi.getBufferId() == OFPacketOut.BUFFER_ID_NONE) {
        	//==//System.out.println("packet in data"+OFMessage.getDataAsString(sw, pi, cntx));
        	byte[] packetData=pkt;
        	// byte[] packetData = new byte[]{(byte)0xab,(byte)0xcd};
            poLength += packetData.length;
            po.setPacketData(packetData);
        }
        Ethernet eth;
        if(cntx!=null)
        {
        eth = IFloodlightProviderService.bcStore.get(cntx,
                IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
        
        }
      //  po.setBufferId(OFPacketOut.BUFFER_ID_NONE);
        po.setLength(poLength);
        
       // po.setBufferId(OFPacketOut.BUFFER_ID_NONE);
        //==//System.out.println("Packet out Message"+po.getDataAsString(sw, po, null));
        try {
            //counterStore.updatePktOutFMCounterStore(sw, po);
           // messageDamper.write(sw, po, cntx);
        	
        	sw.write(po, cntx);
        	//==//System.out.println("Hoooooooooooo");
        } catch (IOException e) {
        	//==//System.out.println("Failure writing packet out"+ e);
        }
    }

    
	public byte[] createpacket(IOFSwitch sw,short outport )
	{
		OFPhysicalPort ofpPort = sw.getPort(outport);
		//==//System.out.println("Packet created");
		int Rand_dst_IP = IPv4.toIPv4Address("10.0.0.5");
		int Rand_src_IP = IPv4.toIPv4Address("10.0.0.6");
		byte[] Rand_Dest_MAC = Ethernet.toMACAddress("ff:ff:ff:ff:ff:ff");
		//byte[] Rand_src_MAC = Ethernet.toMACAddress();
		IPacket ippacket = new Ethernet()
		.setSourceMACAddress(ofpPort.getHardwareAddress())
		.setDestinationMACAddress(Rand_Dest_MAC)
		.setEtherType(Ethernet.TYPE_IPv4)
		.setPayload(
			new IPv4()
			.setDestinationAddress(Rand_dst_IP)
			.setSourceAddress(Rand_src_IP)
			.setProtocol(IPv4.PROTOCOL_UDP));
		return ippacket.serialize();
	}
	
	/*
	/**
	 * Sends a packet out to the switch
	 */
/*	private void pushPacket(IOFSwitch sw ) {
		
		// create an OFPacketOut for the pushed packet
        OFPacketOut po = (OFPacketOut) floodlightProvider.getOFMessageFactory()
                		.getMessage(OFType.PACKET_OUT);        
        
        // Update the inputPort and bufferID
        po.setInPort((short)1);
        po.setBufferId(OFPacketOut.BUFFER_ID_NONE);
        po.setType(OFType.PACKET_OUT);
           
        //Actions
        
        List<OFAction> actions = new ArrayList<OFAction>();      
        actions.add(new OFActionOutput((short)2,(short)0xffff));
       
        
        // Set the actions to apply for this packet		
		po.setActions(actions);
		po.setActionsLength((short)actions.size());
		//==//System.out.println("Actions Length"+actions.size());
		//byte[] packetData =1;
        // Set data if it is included in the packet in but buffer id is NONE
      //  if (pi.getBufferId() == OFPacketOut.BUFFER_ID_NONE) {
         //   byte[] packetData = pi.getPacketData();
           // po.setLength(U16.t(OFPacketOut.MINIMUM_LENGTH
             //       + po.getActionsLength() + packetData.length));
            //po.setPacketData(packetData);
     //   } else {
            po.setLength(U16.t(OFPacketOut.MINIMUM_LENGTH + po.getActionsLength()));
           
            //==//System.out.println("minimum length"+OFPacketOut.MINIMUM_LENGTH+" uint of min length"+U16.t(OFPacketOut.MINIMUM_LENGTH));
            //==//System.out.println("Packetout size"+po.getLength());

    //    }        
            //==//System.out.println("Check it"+po.getDataAsString(sw, po, null));
        
        logger.debug("Push packet to switch: "+po);
        // Push the packet to the switch
        try 
        {
            sw.write(po, null);

            //==//System.out.println("Successfully written");
        } catch (Exception e) {
           //==//System.out.println("failed to write packetOut: "+ e);
        }
	}*/

	/**
	 * Sends a packet out to the switch
	 */
	//private void pushPacket(IOFSwitch sw, OFPacketIn pi,ArrayList<OFAction> actions, short actionsLength) {

	/*private void pushPacket(IOFSwitch sw, OFPacketIn pi) {	
		// create an OFPacketOut for the pushed packet
        OFPacketOut po = (OFPacketOut) floodlightProvider.getOFMessageFactory().getMessage(OFType.PACKET_OUT);  
        short packetOutLength;
        
        // Update the inputPort and bufferID
        po.setInPort(pi.getInPort());
       po.setPacketData(pi.getPacketData());
        //po.setBufferId(pi.getBufferId());
        List<OFAction> actions = new ArrayList<OFAction>();      
        actions.add(new OFActionOutput((short)2,(short)0));
        po.setActions(actions);
        
        //po.setActionsLength((short)actions.size());
        
        po.setPacketData(pi.getPacketData());
        //==//System.out.println("Writing packet");
        // finally, set the total length
        packetOutLength = (short) (po.getActionsLength()+ po.getPacketData().length + po.MINIMUM_LENGTH);
        po.setLength(packetOutLength);  
        
        //==//System.out.println("Printing Packetout"+po);  
        // Set the actions to apply for this packet		
        
		
	        
        // Set data if it is included in the packet in but buffer id is NONE
       /* if (pi.getBufferId() == OFPacketOut.BUFFER_ID_NONE) {
            byte[] packetData = pi.getPacketData();
            po.setLength(U16.t(OFPacketOut.MINIMUM_LENGTH
                    + po.getActionsLength() + packetData.length));
            po.setPacketData(packetData);
        } else {
            po.setLength(U16.t(OFPacketOut.MINIMUM_LENGTH
                    + po.getActionsLength()));
        }       
        
        //logger.debug("Push packet to switch: "+po);
        
       
        
        // Push the packet to the switch
        try {
            sw.write(po, null);
        } catch (IOException e) {
            logger.error("failed to write packetOut: ", e);
        }
	}*/
	
	/**
	 * Finds the switch and port to which the destination device is connected.
	 */
	private SwitchPort findDeviceAttachment(long mac) {
		// Find device based on MAC address address
		Iterator<? extends IDevice> deviceIterator = 
				deviceProvider.queryDevices(mac, null, null, null, null);
		
		// Select first matching device
		if (deviceIterator.hasNext()) {
			IDevice device = deviceIterator.next();
			
			// Get device attachment points
			SwitchPort[] deviceSwitchPorts = device.getAttachmentPoints();
			
			// Select first matching attachment point
			if (deviceSwitchPorts.length >= 1) {
				return deviceSwitchPorts[0];
			}
		}
		return null;
	}
	
	/**
	 * Print a list of all devices in the network.
	 */
	private void printDevices() {
        //logger.debug("DEVICES");
		Collection<? extends IDevice> devices = deviceProvider.getAllDevices();
		for (IDevice device : devices) {
			//logger.debug("MAC="+device.getMACAddressString());
		}
	}
	
	/**
	 * Print a list of all switches in the network.
	 */
	private void printSwitches() {
        //logger.debug("SWITCHES");
		Map<Long,IOFSwitch> switches = floodlightProvider.getSwitches();
		for (IOFSwitch sw : switches.values()) {
			//logger.debug("SwitchId="+sw.getId());
		}
	}
	
	/**
	 * Print a list of all links in the network
	 */
	private void printLinks() {
		//System.out.println("Printliks called");
       // logger.debug("LINKS");
		Map<Link,LinkInfo> links = linkDiscoveryProvider.getLinks();
		//Map<Link,Long>lat=linkDiscoveryProvider.getLatency();
		
		for (@SuppressWarnings("unused") Link link : links.keySet()) {
		 //==//System.out.println("SrcSwitch="+link.getSrc()+" SrcPort="+link.getSrcPort()+", DstSwitch="+link.getDst()+", DstPort="+link.getDstPort());
		}
		
		Iterator<?> it= lat_final.entrySet().iterator();
		
		while(it.hasNext())
		{
		@SuppressWarnings("unchecked")
		Entry<Link,Long> o=(Entry<Link, Long>) it.next();
		//System.out.println("check");
		//System.out.println("Test Source Switch "+o.getKey().getSrc()+", Source Port "+o.getKey().getSrcPort()+", Destination switch "+o.getKey().getDst()+", Destination Port "+o.getKey().getDstPort());
		//System.out.println("Latency MyTest"+o.getValue());
			//while(l.entrySet().iterator()!=)
		//==//System.out.println("Map op\n");
		//	it.toString();
			////==//System.out.println("SrcSwitch="+lin.getSrc()+" SrcPort="+lin.getSrcPort()	+", DstSwitch="+lin.getDst()+", DstPort="+lin.getDstPort());
			
		}
	}
	
	
	public void writepacket(OFMessage msg)  {
		
					Map<Long,IOFSwitch>map=floodlightProvider.getSwitches();
					Map<Link,LinkInfo> links = linkDiscoveryProvider.getLinks();
					int i=0;
					OFPacketIn packetin=(OFPacketIn)msg;
						for (Link link : links.keySet()) {						
							OFPacketOut packetOutMessage = (OFPacketOut) floodlightProvider.getOFMessageFactory().getMessage(OFType.PACKET_OUT);
					        short packetOutLength = (short)OFPacketOut.MINIMUM_LENGTH; // starting length
					        
					        
					        // Set buffer_id, in_port, actions_len
					        packetOutMessage.setBufferId(OFPacketOut.BUFFER_ID_NONE);
					        packetOutMessage.setInPort((short)OFActionOutput.MINIMUM_LENGTH);
					   //   packetOutMessage.setActionsLength((short)OFActionOutput.MINIMUM_LENGTH);
								        
					        List<OFAction> actions = new ArrayList<OFAction>();      
					        actions.add(new OFActionOutput((short)link.getSrcPort(),(short)0));
					        packetOutMessage.setActions(actions);
					       
					        packetOutMessage.setPacketData(packetin.getPacketData());
					        //==//System.out.println("Interswitch delay");
					        // finally, set the total length
					        packetOutLength += (short) (packetOutMessage.getActionsLength()+ packetOutMessage.getPacketData().length);
					        packetOutMessage.setLength(packetOutLength);  
					        
					        //==//System.out.println("Printing Packetout"+packetOutMessage);  
					        // and write it out
					        try {
					        	for (Entry<Long,IOFSwitch>entry:map.entrySet())
					        	{
					        		if(entry.getValue().getId()==link.getSrc())
					        		{
					        		//	FloodlightContext cntx= new FloodlightContext();
					        		//	cntx.getStorage().put("name", new ARP());
									//	messageDamper.write(entry.getValue(), packetOutMessage, cntx, true);
					        		entry.getValue().write(packetOutMessage, null);
					        		 //==//System.out.println("packet out sent in switch"+entry.getValue().getId());
						            //==//System.out.println("packet out sent in port"+link.getSrcPort());
					        		}
					        	}
					            
					        } catch (IOException e) {
					           // Logger log = null;
								//log.error("Failed to write {} to switch {}: {}", new Object[]{ packetOutMessage,link.getDst(), e });
					        }
					        
					        i++;
					        
					        if(i==1)
					        	break;
					        
					}
					/*Ethernet eth=new Ethernet();
					//==//System.out.println("Destination mac"+eth.getDestinationMAC().toLong());
					//==//System.out.println("Source Mac"+eth.getSourceMAC().toLong());*/
					
			    	
				}
		
	
	
	
}

class DemoTimerTask extends Thread {
	
	public Map<IOFSwitch ,Long>switch_latency_send=new HashMap<IOFSwitch,Long>();
	IFloodlightProviderService floodlightProvider;
	IOFMessageListener listener;
	
	
	public Map<IOFSwitch ,Long> get_map()
	{
		return switch_latency_send;
	}
	
	public DemoTimerTask(IFloodlightProviderService floodlightProvider,IOFMessageListener listener) {
		this.floodlightProvider=floodlightProvider;
		this.listener=listener;
	
	}
	public void run()  {
		
	//System.out.println("In thread");
		//==//System.out.println("Running=========================");
		//==//System.out.println("Delay between Controller and switch");
		long past=System.currentTimeMillis();
		//IOFMessageListener listener = new Cost();
		
		while(true)
		{	
		
			long present;
			
			present=System.currentTimeMillis();
			
				if(present-past>10000)
				{
					
				past=present;
				OFStatisticsRequest req = new OFStatisticsRequest();
				req.setStatisticType(OFStatisticsType.FLOW);
				int requestLength = req.getLengthU();
				OFFlowStatisticsRequest specificReq = new OFFlowStatisticsRequest();
				OFMatch match = new OFMatch();
				match.setWildcards(0xffffffff);
				specificReq.setMatch(match);
				specificReq.setOutPort(OFPort.OFPP_NONE.getValue());
				specificReq.setTableId((byte) 0xff);
				req.setStatistics(Collections.singletonList((OFStatistics)specificReq));
				requestLength += specificReq.getLength();
				req.setLengthU(requestLength);
				Map<Long,IOFSwitch> map = floodlightProvider.getSwitches();
				int i=0;
				for (Map.Entry<Long,IOFSwitch> entry : map.entrySet()) {
					try {
					//	//==//System.out.println("Key Entry:"+entry.getKey());
						i++;
					//	//==//System.out.println("Request time:"+System.currentTimeMillis()+"for Switch "+i+" :");
						
					entry.getValue().sendStatsQuery(req, req.getXid(), listener);
					switch_latency_send.put(entry.getValue(), present);
					
					/*
					 * Get statistical information for every switch
					 *
					 */
					List<OFFlowStatisticsReply> reply=getFlows(entry.getValue(),(short)1);
					ListIterator<OFFlowStatisticsReply> iter = reply.listIterator();
					//System.out.println("Hello openflow");
					while(iter.hasNext())
					{
					 //System.out.println("Test openflow"+ iter.next().toString() +"Success");	
					}
					
					}
					catch (IOException ex) {
					//==//System.out.println("Exception : "+ex.getMessage());
					}
				}
				////==//System.out.println("Switch added successfully");
				
				
				for (Map.Entry<IOFSwitch,Long> entry : switch_latency_send.entrySet()) {
				//	//==//System.out.println("Key and Value Pair");
				//	//==//System.out.println(entry.getKey());
				//	//==//System.out.println(entry.getValue());
					
				}
			
			}
				
		}

	}
	
	

	/**
	*
	* @param sw
	* the switch object that we wish to get flows from
	* @param outPort
	* the output action port we wish to find flows with

	* @return a list of OFFlowStatisticsReply objects or essentially flows
	*/
	 public List<OFFlowStatisticsReply> getFlows(IOFSwitch sw, Short outPort) { // Change what the list returns to your stat type

		 //==//System.out.println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$======");
		 ArrayList<OFFlowStatisticsReply> statsReply = new ArrayList<OFFlowStatisticsReply>();
		 List<OFStatistics> values = null;
		 Future future;


		 // Statistics request object for getting flows
		         OFStatisticsRequest req = new OFStatisticsRequest();
		         req.setStatisticType(OFStatisticsType.FLOW); // Change to your stat type here, and modify the following variables(eg. remove outPort since that's flow stat specific)

		         int requestLength = req.getLengthU();
		 OFFlowStatisticsRequest specificReq = new OFFlowStatisticsRequest();
		         specificReq.setMatch(new OFMatch().setWildcards(0xffffffff));

		         specificReq.setOutPort(outPort);
		         specificReq.setTableId((byte) 0xff);
		         req.setStatistics(Collections.singletonList((OFStatistics)specificReq));
		         requestLength += specificReq.getLength();
		         req.setLengthU(requestLength);
		         
		         try {
		          ////==//System.out.println(sw.getStatistics(req));
		          future = (Future) sw.getStatistics(req);
		             values = ((java.util.concurrent.Future<List<OFStatistics>>) future).get(10, TimeUnit.SECONDS);
		             if(values != null){
		              for(OFStatistics stat : values){
		              statsReply.add((OFFlowStatisticsReply)stat);
		              }
		             }
		         } catch (Exception e) {

		             Logger log = null;
					//log.error("Failure retrieving statistics from switch " + sw, e);

		         }
		         //==//System.out.println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$");
		         return statsReply;

		 }
	
	 
}

class Interswitchdelay extends Thread {
	
	public Map<IOFSwitch ,List<Long>>switch_latency_send=new HashMap<IOFSwitch,List<Long>>();
	IFloodlightProviderService floodlightProvider;
	IOFMessageListener listener;
	FloodlightContext cnt;
	ILinkDiscoveryService linkDiscoveryProvider;
	OFMessageDamper messageDamper;
    protected static final short IDLE_TIMEOUT_DEFAULT = 5;
    protected static final short HARD_TIMEOUT_DEFAULT = 0;
    protected static final short PRIORITY_DEFAULT = 100;
	public Map<IOFSwitch ,List<Long>> get_map()
	{
		return switch_latency_send;
	}
	
	public Interswitchdelay(IFloodlightProviderService floodlightProvider,IOFMessageListener listener,ILinkDiscoveryService linkDiscoveryProvider,OFMessageDamper messageDamper) {
		this.floodlightProvider=floodlightProvider;
		this.listener=listener;
		this.linkDiscoveryProvider=linkDiscoveryProvider;
		this.messageDamper=messageDamper;
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this.listener);
	}

	
	@SuppressWarnings("null")
	public void run()  {
		
	
		//==//System.out.println("Running=========================");
		long past=System.currentTimeMillis();
		//IOFMessageListener listener = new Cost();
		long present;
		while(true)
		{	
				present=System.currentTimeMillis();
			
				if(present-past>10000)
				{
					
					past=present;
					Map<Long,IOFSwitch>map=floodlightProvider.getSwitches();
					//==//System.out.println("thread running");
					//for every link check the value of delay so transmit the packet through every link
					Map<Link,LinkInfo> links = linkDiscoveryProvider.getLinks();
					int i=0;
						for (Link link : links.keySet()) {
									
					
					OFMatch match1= new OFMatch();
			        
			        //==//System.out.println(match1);
			        
			       
			        
						 IOFSwitch iofSwitch = floodlightProvider.getSwitches().get(link.getSrc());
						 IOFSwitch iofSwitch1 = floodlightProvider.getSwitches().get(link.getDst());
					        

					//        OFPhysicalPort ofpPort = iofSwitch.getPort(link.getSrcPort());
					        
					 //       OFPhysicalPort ofpdestPort = iofSwitch1.getPort(link.getDstPort());
					        
					        Ethernet ethernet;
							ethernet = (Ethernet) new Ethernet()
							.setEtherType(Ethernet.TYPE_IPv4)
				            .setSourceMACAddress(Ethernet.toMACAddress("10:10:10:10:10:10"))
				            .setDestinationMACAddress(Ethernet.toMACAddress("11:11:11:11:11:11"))
				            .setPayload(new IPv4()
							.setProtocol(IPv4.PROTOCOL_UDP)
							.setDestinationAddress("10.0.0.5")
							.setSourceAddress("10.0.0.4")
							.setPayload(null));				            
					       
						
							OFPacketOut packetOutMessage = (OFPacketOut) floodlightProvider.getOFMessageFactory().getMessage(OFType.PACKET_OUT);
					        short packetOutLength = (short)OFPacketOut.MINIMUM_LENGTH; // starting length
					        
					        
					        // Set buffer_id, in_port, actions_len
					        packetOutMessage.setBufferId(OFPacketOut.BUFFER_ID_NONE);
					        packetOutMessage.setInPort((short)OFPort.OFPP_NONE.getValue()); 
					   //   packetOutMessage.setActionsLength((short)OFActionOutput.MINIMUdataLayerDestinationM_LENGTH);
								        
					        List<OFAction> actions = new ArrayList<OFAction>();      
					        actions.add(new OFActionOutput((short)OFPort.OFPP_NONE.getValue(),(short)0));
					        packetOutMessage.setActions(actions);
					       
					        packetOutMessage.setPacketData(ethernet.serialize());
					        //==//System.out.println("Interswitch delay");
					        // finally, set the total length
					        packetOutLength += (short) (packetOutMessage.getActionsLength()+ packetOutMessage.getPacketData().length);
					        packetOutMessage.setLengthU(packetOutLength);  
					        
					        //==//System.out.println("Printing Packetout"+packetOutMessage);
					        
					        //Flow module
					        match1.setDataLayerDestination(Ethernet.toMACAddress("11:11:11:11:11:11"));
					        match1.setDataLayerSource(Ethernet.toMACAddress("10:10:10:10:10:10"));
			  
					        OFFlowMod flowMod = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
					        flowMod.setMatch(match1);
					        flowMod.setCookie(Cost.Cost_COOKIE);
					        flowMod.setCommand(OFFlowMod.OFPFC_ADD);
					        flowMod.setIdleTimeout(Interswitchdelay.IDLE_TIMEOUT_DEFAULT);
					        flowMod.setHardTimeout(Interswitchdelay.HARD_TIMEOUT_DEFAULT);
					        flowMod.setPriority(Interswitchdelay.PRIORITY_DEFAULT);
					        flowMod.setBufferId(OFPacketOut.BUFFER_ID_NONE);
					        flowMod.setOutPort(link.getSrcPort());
					        
					        
					        

				      flowMod.setActions(Arrays.asList((OFAction) new OFActionOutput(link.getSrcPort(), (short) 0xffff)));
					  flowMod.setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));
					      
					        
					        
					        
					        // and write it out
					        try {
					        	
					        		//	FloodlightContext cntx= new FloodlightContext();
					        		//	cntx.getStorage().put("name", new ARP());
									//	messageDamper.write(entry.getValue(), packetOutMessage, cntx, true);
					      //   iofSwitch.write(flowMod,null);
					        	
					        	iofSwitch.write(packetOutMessage, null);
					        //	iofSwitch.flush();

					        		/* //==//System.out.println("packet out sent in switch"+iofSwitch.getId());
						            //==//System.out.println("packet out sent in port"+link.getSrcPort());*/
						        	//iofSwitch.flush();	
					            
					        } catch (IOException e) {
					            Logger log = null;
								//log.error("Failed to write {} to switch {}: {}", new Object[]{ packetOutMessage,link.getDst(), e });
					        }
					        
					        i++;
					        
					        if(i==1)
					        	break;
					        
					}
					/*Ethernet eth=new Ethernet();
					//==//System.out.println("Destination mac"+eth.getDestinationMAC().toLong());
					//==//System.out.println("Source Mac"+eth.getSourceMAC().toLong());*/
					
			    	
				}
				
		}
	}
	
 
}


class StaticTopology extends Thread {
	
	//public Map<Long,Long,Short,Short,Long> topology=new Map<Long,Long,Short,Short,Long>();
	Long src_sw;
	Long dest_sw;
	Short src_port;
	Short dest_port;
	Long link;
	IFloodlightProviderService floodlightProvider;
	IOFMessageListener listener;
	FloodlightContext cnt;
	//Map for every switch holding their corresponding ports 
	//@param Long for DPID of switch
	//@param List<Short> for all Port associated with it
	Map<Long,List<Short>>allswitchandport=new HashMap<Long,List<Short>>();
	
	//Map for every switch holding their corresponding ports 
		//@param Long for DPID of switch
		//@param Short for Port
		Map<Long,Short>switchandport=new HashMap<Long,Short>();
		
	
	//Map for every switch holding their corresponding ports 
		//@param Long for Link ID
		//@param Map<Long,Short> for switch port pair
	Map<Long, Map<Long,Short> > statictopology=new HashMap<Long, Map<Long,Short> >(10);
	
	
	
	public StaticTopology() {
	
		}
	
	
	public void gettopology()  {
		    	try
		    	{
		    		BufferedReader br = new BufferedReader(new FileReader("/home/thomas/Documents/topology.conf"));
		    		//==//System.out.println("File has been loaded");
		    		String line = null;
		    		while((line = br.readLine())!=null)
		    		{
		    			String[] str = line.split(",");
		    			src_sw=Long.parseLong(str[0]);
		    			dest_sw=Long.parseLong(str[1]);
		    			src_port=Short.parseShort(str[2]);
		    			dest_port=Short.parseShort(str[3]);
		    			link=Long.parseLong(str[4]);
		    			switchandport=new HashMap<Long,Short>(4);
		    			switchandport.put(src_sw, src_port);
		    			switchandport.put(dest_sw, dest_port);
		    			statictopology.put(link,switchandport );
		    			//switchandport.clear();
		    		}
		    		br.close();
		    		//isConfigLoaded = true;
		    	}catch(IOException ex){
		    		//==//System.out.println("Error in reading file##### {}"+ex.toString());
		    	}
		    }
	
	
	public void printtopology()  {
    	
    		
    		//==//System.out.println("topology");
    		for (Entry<Long, Map<Long,Short> > entry : statictopology.entrySet()) {
    			//==//System.out.println("Link "+entry.getKey());
    			
    			for(Entry<Long,Short> entry1:entry.getValue().entrySet())
    			{
    				
    			}
    				
    		}
     }

		
}



