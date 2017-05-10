package net.floodlightcontroller.mmylearningswitch;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.MacVlanPair;
import net.floodlightcontroller.counter.ICounterStoreService;
import net.floodlightcontroller.learningswitch.ILearningSwitchService;
import net.floodlightcontroller.packet.ARP;
//import net.floodlightcontroller.learningswitch.LearningSwitch;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.restserver.IRestApiService;

import org.openflow.protocol.OFError;
import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFFlowRemoved;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.util.HexString;
import org.openflow.util.LRULinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MmyLearningSwitch 
    implements IFloodlightModule, ILearningSwitchService, IOFMessageListener {
    protected static Logger log = LoggerFactory.getLogger(MmyLearningSwitch.class);
    
    // Module dependencies
    protected IFloodlightProviderService floodlightProvider;
    protected ICounterStoreService counterStore;
    //protected IRestApiService restApi;
    
    // Stores the learned state for each switch
    protected Map<IOFSwitch, Map<MacVlanPair,Short>> macSliceToSwitchPortMap;
    protected Map<Short,Short> sliceportMap;

    // flow-mod - for use in the cookie
    public static final int LEARNING_SWITCH_APP_ID = 1;
    // LOOK! This should probably go in some class that encapsulates
    // the app cookie management
    public static final int APP_ID_BITS = 12;
    public static final int APP_ID_SHIFT = (64 - APP_ID_BITS);
    public static final long LEARNING_SWITCH_COOKIE = (long) (LEARNING_SWITCH_APP_ID & ((1 << APP_ID_BITS) - 1)) << APP_ID_SHIFT;
    
    // more flow-mod defaults 
    protected static final short IDLE_TIMEOUT_DEFAULT = 5;
    protected static final short HARD_TIMEOUT_DEFAULT = 0;
    protected static final short PRIORITY_DEFAULT = 100;
    
    // for managing our map sizes
    protected static final int MAX_MACS_PER_SWITCH  = 1000;    

    // normally, setup reverse flow as well. Disable only for using cbench for comparison with NOX etc.
    protected static final boolean LEARNING_SWITCH_REVERSE_FLOW = true;
    
    /**
     * @param floodlightProvider the floodlightProvider to set
     */
    public void setFloodlightProvider(IFloodlightProviderService floodlightProvider) {
        this.floodlightProvider = floodlightProvider;
    }
    
    @Override
    public String getName() {
        return "mylearningswitch";
    }

    /**
     * Adds a host to the MAC/Slice->SwitchPort mapping
     * @param sw The switch to add the mapping to
     * @param mac The MAC address of the host to add
     * @param slice The Slice that the host is on
     * @param portVal The switchport that the host is on
     */
    protected void addToPortMap(IOFSwitch sw, long mac, short slice, short portVal) {
        Map<MacVlanPair,Short> swMap = macSliceToSwitchPortMap.get(sw);
        
        if (slice == (short) 0xffff) {
            // OFMatch.loadFromPacket sets Slice ID to 0xffff
            // for our purposes that is equivalent to the default Slice ID 0
            slice = 0;
        }
        
        if (swMap == null) {
            // May be accessed by REST API so we need to make it thread safe
            swMap = Collections.synchronizedMap(new LRULinkedHashMap<MacVlanPair,Short>(MAX_MACS_PER_SWITCH));
            macSliceToSwitchPortMap.put(sw, swMap);
        }
        swMap.put(new MacVlanPair(mac, slice), portVal);        
    }
    
    /**
     * Removes a host from the MAC/Slice->SwitchPort mapping
     * @param sw The switch to remove the mapping from
     * @param mac The MAC address of the host to remove
     * @param slice The Slice that the host is on
     */
    protected void removeFromPortMap(IOFSwitch sw, long mac, short slice) {
        if (slice == (short) 0xffff) {
            slice = 0;
        }
        Map<MacVlanPair,Short> swMap = macSliceToSwitchPortMap.get(sw);
        if (swMap != null)
            swMap.remove(new MacVlanPair(mac, slice));
    }

    /**
     * Get the port that a MAC/Slice pair is associated with
     * @param sw The switch to get the mapping from
     * @param mac The MAC address to get
     * @param slice The Slice number to get
     * @return The port the host is on
     */
    public Short getFromPortMap(IOFSwitch sw, long mac, short slice) {
    	System.out.println("RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR");
    	System.out.println("Tobe found mac"+mac);
    	System.out.println("Tobe found slice"+slice);
    	System.out.println(sw.getId());
    	System.out.println(sw.getAttribute(getName()));
    	System.out.println(sw.getStringId());
        if (slice == (short) 0xffff) {
            slice = 0;
        }
        Map<MacVlanPair,Short> swMap = macSliceToSwitchPortMap.get(sw);
        if (swMap != null)
        {System.out.println("Entry foundssssssssssssssssssssssssssssssssss");
            for (Entry <MacVlanPair,Short>entry : swMap.entrySet())
            {
            	/*System.out.println("Mac"+entry.getKey().mac.longValue());
        		System.out.println("Vlan"+entry.getKey().vlan.longValue());
        		System.out.println("Port"+entry.getValue());*/
            	if(entry.getKey().mac.longValue()==mac)
            	{
            		System.out.println("Valid");
            		if(entry.getKey().vlan.longValue()==slice)
            		return entry.getValue();
            		else
            			return 255;
            	}
            }
            
        }
        
        // if none found
        return null;
    }
    
    /**
     * Clears the MAC/Slice -> SwitchPort map for all switches
     */
    public void clearLearnedTable() {
    	macSliceToSwitchPortMap.clear();
    }
    
    /**
     * Clears the MAC/Slice -> SwitchPort map for a single switch
     * @param sw The switch to clear the mapping for
     */
    public void clearLearnedTable(IOFSwitch sw) {
        Map<MacVlanPair, Short> swMap = macSliceToSwitchPortMap.get(sw);
        if (swMap != null)
            swMap.clear();
    }
    
    @Override
    public synchronized Map<IOFSwitch, Map<MacVlanPair,Short>> getTable() {
        return macSliceToSwitchPortMap;
    }
    
    /**
     * Writes a OFFlowMod to a switch.
     * @param sw The switch tow rite the flowmod to.
     * @param command The FlowMod actions (add, delete, etc).
     * @param bufferId The buffer ID if the switch has buffered the packet.
     * @param match The OFMatch structure to write.
     * @param outPort The switch port to output it to.
     */
    private void writeFlowMod(IOFSwitch sw, short command, int bufferId,
            OFMatch match, short outPort) {
                   
        OFFlowMod flowMod = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
        flowMod.setMatch(match);
        flowMod.setCookie(MmyLearningSwitch.LEARNING_SWITCH_COOKIE);
        flowMod.setCommand(command);
        flowMod.setIdleTimeout(MmyLearningSwitch.IDLE_TIMEOUT_DEFAULT);
        flowMod.setHardTimeout(MmyLearningSwitch.HARD_TIMEOUT_DEFAULT);
        flowMod.setPriority(MmyLearningSwitch.PRIORITY_DEFAULT);
        flowMod.setBufferId(bufferId);
        flowMod.setOutPort((command == OFFlowMod.OFPFC_DELETE) ? outPort : OFPort.OFPP_NONE.getValue());
        flowMod.setFlags((command == OFFlowMod.OFPFC_DELETE) ? 0 : (short) (1 << 0)); // OFPFF_SEND_FLOW_REM
        flowMod.setActions(Arrays.asList((OFAction) new OFActionOutput(outPort, (short) 0xffff)));
        flowMod.setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));

        if (log.isTraceEnabled()) {
            log.trace("{} {} flow mod {}", 
                      new Object[]{ sw, (command == OFFlowMod.OFPFC_DELETE) ? "deleting" : "adding", flowMod });
        }

        counterStore.updatePktOutFMCounterStore(sw, flowMod);
        
        // and write it out
        try {
            sw.write(flowMod, null);
        } catch (IOException e) {
            log.error("Failed to write {} to switch {}", new Object[]{ flowMod, sw }, e);
        }
    }
    
    /**
     * Writes an OFPacketOut message to a switch.
     * @param sw The switch to write the PacketOut to.
     * @param packetInMessage The corresponding PacketIn.
     * @param egressPort The switchport to output the PacketOut.
     */
    private void writePacketOutForPacketIn(IOFSwitch sw,OFPacketIn packetInMessage,short egressPort) {
        
    	
    	OFPacketOut packetOutMessage = (OFPacketOut) floodlightProvider.getOFMessageFactory().getMessage(OFType.PACKET_OUT);
        short packetOutLength = (short)OFPacketOut.MINIMUM_LENGTH; // starting length

        // Set buffer_id, in_port, actions_len
        packetOutMessage.setBufferId(packetInMessage.getBufferId());
        packetOutMessage.setInPort(packetInMessage.getInPort());
        packetOutMessage.setActionsLength((short)OFActionOutput.MINIMUM_LENGTH);
        packetOutLength += OFActionOutput.MINIMUM_LENGTH;
        
        // set actions
        List<OFAction> actions = new ArrayList<OFAction>(1);      
        actions.add(new OFActionOutput(egressPort, (short) 0));
        packetOutMessage.setActions(actions);

        // set data - only if buffer_id == -1
        if (packetInMessage.getBufferId() == OFPacketOut.BUFFER_ID_NONE) {
            byte[] packetData = packetInMessage.getPacketData();
            packetOutMessage.setPacketData(packetData); 
            packetOutLength += (short)packetData.length;
        }
        
        // finally, set the total length
        packetOutMessage.setLength(packetOutLength);              
            
        // and write it out
        try {
        	counterStore.updatePktOutFMCounterStore(sw, packetOutMessage);
            sw.write(packetOutMessage, null);
        } catch (IOException e) {
            log.error("Failed to write {} to switch {}: {}", new Object[]{ packetOutMessage, sw, e });
        }
    	
    	
    }
    
    
    
    
    
    protected boolean isConfigLoaded = false;
    void loadsliceconf()
    {
    	try
    	{
    		BufferedReader br = new BufferedReader(new FileReader("/home/thomas/Documents/SDN/slice /slice.conf"));
    		log.info("File has been loaded");
    		String line = null;
    		while((line = br.readLine())!=null)
    		{
    			String[] str = line.split(",");
    			short port = Short.parseShort(str[0]);
    			short slice = Short.parseShort(str[1]);
    			sliceportMap.put(port, slice);
    		}
    		br.close();
    		isConfigLoaded = true;
    	}catch(IOException ex){
    		log.info("Error in reading file {}",ex.getMessage());
    	}
    }
    /**
     * Processes a OFPacketIn message. If the switch has learned the MAC/Slice to port mapping
     * for the pair it will write a FlowMod for. If the mapping has not been learned the 
     * we will flood the packet.
     * @param sw
     * @param pi
     * @param cntx
     * @return
     */
    private Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
    	Ethernet eth = IFloodlightProviderService.bcStore.get(cntx,
				IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
    	
    	//sw.clearAllFlowMods();
    	System.out.println("received packet##############################");
    		System.out.println(pi.getType());    		
    	// Read in packet data headers by using OFMatch
        OFMatch match = new OFMatch();
        match.loadFromPacket(pi.getPacketData(), pi.getInPort());
        Long sourceMac = Ethernet.toLong(match.getDataLayerSource());
        Long destMac = Ethernet.toLong(match.getDataLayerDestination());
        
        Short portnum = pi.getInPort();
        Short slice = sliceportMap.get(portnum);//match.getDataLayerVirtualLan();
        if(eth.getPayload() instanceof ARP)
    	{
    		System.out.println("ARP");
    		for(Entry<Short,Short>entry: sliceportMap.entrySet())
            {
            	if(slice==entry.getValue()&& portnum!=entry.getKey())
            	{
            		System.out.println("Flooded to port :"+entry.getKey());
            		 this.writePacketOutForPacketIn(sw, pi,entry.getKey());
            		 
            	}
            	
            }
    		
    	}
        
        System.out.println("Input port 0000000000000000000:"+portnum);
        System.out.println("Slice Number 0000000000000000000:"+slice);
        System.out.println("destMac 0000000000000000000:"+destMac);
        System.out.println("sourceMac 0000000000000000000:"+sourceMac);
        System.out.println("DataLayerType"+match.getDataLayerType());
        //if(match.getDataLayerType())
        boolean flag=true;
        for(Entry<Short,Short>entry: sliceportMap.entrySet())
        {
        	if(slice==entry.getValue()&& portnum!=entry.getKey())
        	{
        		System.out.println("Flooded to port :"+entry.getKey());
        		 this.writePacketOutForPacketIn(sw, pi,entry.getKey());
        		 flag=false;
        	}
        	
        }
        /*if (flag==true)
        	this.writePacketOutForPacketIn(sw, pi,(short)0);*/
        
        return Command.CONTINUE;
       /* if ((destMac & 0xfffffffffff0L) == 0x0180c2000000L) {=================================================================================
            if (log.isTraceEnabled()) {
                log.trace("ignoring packet addressed to 802.1D/Q reserved addr: switch {} slice {} dest MAC {}",
                          new Object[]{ sw, slice, HexString.toHexString(destMac) });
            	System.out.println("received packet+++++++++++++++++++++++++++++++++");
            }
            return Command.STOP;
        }
        if ((sourceMac & 0x010000000000L) == 0) {
            // If source MAC is a unicast address, learn the port for this MAC/VLAN
            this.addToPortMap(sw, sourceMac, slice, pi.getInPort());
        	System.out.println("Unicast received packet-------------------------------------------input port "+pi.getInPort());
   
        }=======================================================================================================================================*/
        
       // this.addToPortMap(sw, sourceMac, slice, pi.getInPort());
        // Now output flow-mod and/or packet
        /*System.out.println("Non Unicast-------------------------------------------input port "+pi.getInPort());
        Short outPort = getFromPortMap(sw, destMac, slice);
 
        System.out.println("Port Number 1111111111111111111111111111:"+outPort);
        if (outPort == null) {
            // If we haven't learned the port for the dest MAC/slice, flood it
            // Don't flood broadcast packets if the broadcast is disabled.
            // XXX For LearningSwitch this doesn't do much. The sourceMac is removed
            //     from port map whenever a flow expires, so you would still see
            //     a lot of floods.
            this.writePacketOutForPacketIn(sw, pi, OFPort.OFPP_FLOOD.getValue());
        } else if (outPort == match.getInputPort()) {
            System.out.println("Found Match ==========================");
        	log.trace("ignoring packet that arrived on same port as learned destination:"
                    + " switch {} slice {} dest MAC {} port {}",
                    
                    new Object[]{ sw, slice, HexString.toHexString(destMac), outPort });
            
        }
        
        else if (outPort==255)
        	{
        	System.out.println("Belongs to different slice");
        	// this.(sw, pi, OFPort.OFPP_FLOOD.getValue());
        	 return Command.CONTINUE;
        	}
        	else {
        	
        	System.out.println("Yeppppppppppppppppp{{{{{{{{{==========================");
             
            match.setWildcards(((Integer)sw.getAttribute(IOFSwitch.PROP_FASTWILDCARDS)).intValue()
                    & ~OFMatch.OFPFW_IN_PORT
                    & ~OFMatch.OFPFW_DL_VLAN & ~OFMatch.OFPFW_DL_SRC & ~OFMatch.OFPFW_DL_DST
                    & ~OFMatch.OFPFW_NW_SRC_MASK & ~OFMatch.OFPFW_NW_DST_MASK);
            
            this.writeFlowMod(sw, OFFlowMod.OFPFC_ADD, pi.getBufferId(), match, outPort);
            
            if (LEARNING_SWITCH_REVERSE_FLOW) {
                this.writeFlowMod(sw, OFFlowMod.OFPFC_ADD, -1, match.clone()
                    .setDataLayerSource(match.getDataLayerDestination())
                    .setDataLayerDestination(match.getDataLayerSource())
                    .setNetworkSource(match.getNetworkDestination())
                    .setNetworkDestination(match.getNetworkSource())
                    .setTransportSource(match.getTransportDestination())
                    .setTransportDestination(match.getTransportSource())
                    .setInputPort(outPort),
                    match.getInputPort());
            }
        }
        return Command.CONTINUE;*/
    }

    /**
     * Processes a flow removed message. We will delete the learned MAC/Slice mapping from
     * the switch's table.
     * @param sw The switch that sent the flow removed message.
     * @param flowRemovedMessage The flow removed message.
     * @return Whether to continue processing this message or stop.
     */
    private Command processFlowRemovedMessage(IOFSwitch sw, OFFlowRemoved flowRemovedMessage) {
        if (flowRemovedMessage.getCookie() != MmyLearningSwitch.LEARNING_SWITCH_COOKIE) {
            return Command.CONTINUE;
        }
        if (log.isTraceEnabled()) {
            log.trace("{} flow entry removed {}", sw, flowRemovedMessage);
        }
        OFMatch match = flowRemovedMessage.getMatch();
        // When a flow entry expires, it means the device with the matching source
        // MAC address and Slice either stopped sending packets or moved to a different
        // port.  If the device moved, we can't know where it went until it sends
        // another packet, allowing us to re-learn its port.  Meanwhile we remove
        // it from the macSliceToPortMap to revert to flooding packets to this device.
        this.removeFromPortMap(sw, Ethernet.toLong(match.getDataLayerSource()),
            match.getDataLayerVirtualLan());
        
        // Also, if packets keep coming from another device (e.g. from ping), the
        // corresponding reverse flow entry will never expire on its own and will
        // send the packets to the wrong port (the matching input port of the
        // expired flow entry), so we must delete the reverse entry explicitly.
        this.writeFlowMod(sw, OFFlowMod.OFPFC_DELETE, -1, match.clone()
                .setWildcards(((Integer)sw.getAttribute(IOFSwitch.PROP_FASTWILDCARDS)).intValue()
                        & ~OFMatch.OFPFW_DL_VLAN & ~OFMatch.OFPFW_DL_SRC & ~OFMatch.OFPFW_DL_DST
                        & ~OFMatch.OFPFW_NW_SRC_MASK & ~OFMatch.OFPFW_NW_DST_MASK)
                .setDataLayerSource(match.getDataLayerDestination())
                .setDataLayerDestination(match.getDataLayerSource())
                .setNetworkSource(match.getNetworkDestination())
                .setNetworkDestination(match.getNetworkSource())
                .setTransportSource(match.getTransportDestination())
                .setTransportDestination(match.getTransportSource()),
                match.getInputPort());
        return Command.CONTINUE;
    }
    
    // IOFMessageListener
    
    @Override
    public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
        switch (msg.getType()) {
            case PACKET_IN:
                return this.processPacketInMessage(sw, (OFPacketIn) msg, cntx);
            case FLOW_REMOVED:
                return this.processFlowRemovedMessage(sw, (OFFlowRemoved) msg);
            case ERROR:
                log.info("received an error {} from switch {}", (OFError) msg, sw);
                return Command.CONTINUE;
            default:
            	return this.processPacketInMessage(sw, (OFPacketIn) msg, cntx);
        }
    }

    @Override
    public boolean isCallbackOrderingPrereq(OFType type, String name) {
        return false;
    }

    @Override
    public boolean isCallbackOrderingPostreq(OFType type, String name) {
        return false;
    }

    // IFloodlightModule
    
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        Collection<Class<? extends IFloodlightService>> l = 
                new ArrayList<Class<? extends IFloodlightService>>();
        l.add(ILearningSwitchService.class);
        return l;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService>
            getServiceImpls() {
        Map<Class<? extends IFloodlightService>,
            IFloodlightService> m = 
                new HashMap<Class<? extends IFloodlightService>,
                    IFloodlightService>();
        m.put(ILearningSwitchService.class, this);
        return m;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>>
            getModuleDependencies() {
        Collection<Class<? extends IFloodlightService>> l = 
                new ArrayList<Class<? extends IFloodlightService>>();
        l.add(IFloodlightProviderService.class);
        l.add(ICounterStoreService.class);
        l.add(IRestApiService.class);
        return l;
    }
    
    @Override
    public void init(FloodlightModuleContext context)
            throws FloodlightModuleException {
    	macSliceToSwitchPortMap = 
                new ConcurrentHashMap<IOFSwitch, Map<MacVlanPair,Short>>();
        sliceportMap = new HashMap<Short,Short>(5);
        if(!isConfigLoaded)
        	loadsliceconf();
        
        floodlightProvider =
                context.getServiceImpl(IFloodlightProviderService.class);
        counterStore =
                context.getServiceImpl(ICounterStoreService.class);
        //restApi = context.getServiceImpl(IRestApiService.class);
    }

    @Override
    public void startUp(FloodlightModuleContext context) {
        floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
        floodlightProvider.addOFMessageListener(OFType.FLOW_REMOVED, this);
        floodlightProvider.addOFMessageListener(OFType.ERROR, this);
        //restApi.addRestletRoutable(new LearningSwitchWebRoutable());
    }
}