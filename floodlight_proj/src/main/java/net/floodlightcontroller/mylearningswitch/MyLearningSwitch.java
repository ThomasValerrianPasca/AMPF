/**
*    Copyright 2011, Big Switch Networks, Inc. 
*    Originally created by David Erickson, Stanford University
* 
*    Licensed under the Apache License, Version 2.0 (the "License"); you may
*    not use this file except in compliance with the License. You may obtain
*    a copy of the License at
*
*         http://www.apache.org/licenses/LICENSE-2.0
*
*    Unless required by applicable law or agreed to in writing, software
*    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
*    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
*    License for the specific language governing permissions and limitations
*    under the License.
**/

/**
 * Floodlight
 * A BSD licensed, Java based OpenFlow controller
 *
 * Floodlight is a Java based OpenFlow controller originally written by David Erickson at Stanford
 * University. It is available under the BSD license.
 *
 * For documentation, forums, issue tracking and more visit:
 *
 * http://www.openflowhub.org/display/Floodlight/Floodlight+Home
 **/

package net.floodlightcontroller.mylearningswitch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.staticflowentry.StaticFlowEntryPusher;

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
import org.openflow.protocol.action.OFActionVirtualLanIdentifier;
import org.openflow.util.HexString;
import org.openflow.util.LRULinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.corba.se.impl.encoding.OSFCodeSetRegistry.Entry;

public class MyLearningSwitch 
    implements IFloodlightModule, ILearningSwitchService, IOFMessageListener {
    protected static Logger log = LoggerFactory.getLogger(MyLearningSwitch.class);
    
    // Module dependencies
    protected IFloodlightProviderService floodlightProvider;
    protected ICounterStoreService counterStore;
    protected IRestApiService restApi;
    
    // Stores the learned state for each switch
    protected Map<IOFSwitch, Map<MacVlanPair,Short>> macVlanToSwitchPortMap;
    public Map<Short,Short>vlanport;
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
    
    public Map<Long,Short>macvlan;
    //new vlan;
    
    short vlan1=0;boolean flag=true;int count=0;
    
    /**
     * @param floodlightProvider the floodlightProvider to set
     */
    public void setFloodlightProvider(IFloodlightProviderService floodlightProvider) {
        this.floodlightProvider = floodlightProvider;
    }
    
    @Override
    public String getName() {
        return "learningswitch";
    }

    /**
     * Adds a host to the MAC/VLAN->SwitchPort mapping
     * @param sw The switch to add the mapping to
     * @param mac The MAC address of the host to add
     * @param vlan The VLAN that the host is on
     * @param portVal The switchport that the host is on
     */
    protected void addToPortMap(IOFSwitch sw, long mac, short vlan, short portVal) {
        Map<MacVlanPair,Short> swMap = macVlanToSwitchPortMap.get(sw);
        
        if (vlan == (short) 0xffff) {
            // OFMatch.loadFromPacket sets VLAN ID to 0xffff if the packet contains no VLAN tag;
            // for our purposes that is equivalent to the default VLAN ID 0
            vlan = 0;
        }
        
        if (swMap == null) {
            // May be accessed by REST API so we need to make it thread safe
            swMap = Collections.synchronizedMap(new LRULinkedHashMap<MacVlanPair,Short>(MAX_MACS_PER_SWITCH));
            macVlanToSwitchPortMap.put(sw, swMap);
        }
        swMap.put(new MacVlanPair(mac, vlan), portVal);
    }
    
    /**
     * Removes a host from the MAC/VLAN->SwitchPort mapping
     * @param sw The switch to remove the mapping from
     * @param mac The MAC address of the host to remove
     * @param vlan The VLAN that the host is on
     */
    protected void removeFromPortMap(IOFSwitch sw, long mac, short vlan) {
        if (vlan == (short) 0xffff) {
            vlan = 0;
        }
        Map<MacVlanPair,Short> swMap = macVlanToSwitchPortMap.get(sw);
        if (swMap != null)
            swMap.remove(new MacVlanPair(mac, vlan));
    }

    /**
     * Get the port that a MAC/VLAN pair is associated with
     * @param sw The switch to get the mapping from
     * @param mac The MAC address to get
     * @param vlan The VLAN number to get
     * @return The port the host is on
     */
    public Short getFromPortMap(IOFSwitch sw, long srcmac,long mac) {
    	short dest_vlan=0;
    	short src_vlan=0;
    	short dest_port=0;
    	short vlan=0;
        if (vlan == (short) 0xffff) {
            vlan = 0;
        }
        Map<MacVlanPair,Short> swMap = macVlanToSwitchPortMap.get(sw);
        if (swMap != null)
        {
        	for (java.util.Map.Entry<MacVlanPair, Short> entry : swMap.entrySet())
            {
            	/*System.out.println("Mac"+entry.getKey().mac.longValue());
        		System.out.println("Vlan"+entry.getKey().vlan.longValue());
        		System.out.println("Port"+entry.getValue());*/
            	if(entry.getKey().mac.longValue()==mac)
            	{
            		System.out.println("dest mac Valid");
            		System.out.println(entry.getValue());
            		dest_vlan=entry.getKey().vlan.shortValue();
            		dest_port=entry.getValue().shortValue();
            	}
            	if(entry.getKey().mac.longValue()==srcmac)
            	{
            		System.out.println("src mac Valid");
            		System.out.println(entry.getValue());
            		src_vlan=entry.getKey().vlan.shortValue();
            	}
            	          	
            	
            	
            }
        	if(dest_vlan==src_vlan)
        	{
        		System.out.println("returning destination port"+dest_port);
        	return dest_port;
        	}
        	else if(dest_vlan!=src_vlan)
        		return 255;
        //	return swMap.get(new MacVlanPair(mac, vlan));
        	
        	
        }
            
        
        // if none found
        return null;
    }
    
    /**
     * Clears the MAC/VLAN -> SwitchPort map for all switches
     */
    public void clearLearnedTable() {
        macVlanToSwitchPortMap.clear();
    }
    
    /**
     * Clears the MAC/VLAN -> SwitchPort map for a single switch
     * @param sw The switch to clear the mapping for
     */
    public void clearLearnedTable(IOFSwitch sw) {
        Map<MacVlanPair, Short> swMap = macVlanToSwitchPortMap.get(sw);
        if (swMap != null)
            swMap.clear();
    }
    
    @Override
    public synchronized Map<IOFSwitch, Map<MacVlanPair,Short>> getTable() {
        return macVlanToSwitchPortMap;
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
        // from openflow 1.0 spec - need to set these on a struct ofp_flow_mod:
        // struct ofp_flow_mod {
        //    struct ofp_header header;
        //    struct ofp_match match; /* Fields to match */
        //    uint64_t cookie; /* Opaque controller-issued identifier. */
        //
        //    /* Flow actions. */
        //    uint16_t command; /* One of OFPFC_*. */
        //    uint16_t idle_timeout; /* Idle time before discarding (seconds). */
        //    uint16_t hard_timeout; /* Max time before discarding (seconds). */
        //    uint16_t priority; /* Priority level of flow entry. */
        //    uint32_t buffer_id; /* Buffered packet to apply to (or -1).
        //                           Not meaningful for OFPFC_DELETE*. */
        //    uint16_t out_port; /* For OFPFC_DELETE* commands, require
        //                          matching entries to include this as an
        //                          output port. A value of OFPP_NONE
        //                          indicates no restriction. */
        //    uint16_t flags; /* One of OFPFF_*. */
        //    struct ofp_action_header actions[0]; /* The action length is inferred
        //                                            from the length field in the
        //                                            header. */
        //    };
           
        OFFlowMod flowMod = (OFFlowMod) floodlightProvider.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
        flowMod.setMatch(match);
        flowMod.setCookie(MyLearningSwitch.LEARNING_SWITCH_COOKIE);
        flowMod.setCommand(command);
        flowMod.setIdleTimeout(MyLearningSwitch.IDLE_TIMEOUT_DEFAULT);
        flowMod.setHardTimeout(MyLearningSwitch.HARD_TIMEOUT_DEFAULT);
        flowMod.setPriority(MyLearningSwitch.PRIORITY_DEFAULT);
        flowMod.setBufferId(bufferId);
        flowMod.setOutPort((command == OFFlowMod.OFPFC_DELETE) ? outPort : OFPort.OFPP_NONE.getValue());
        flowMod.setFlags((command == OFFlowMod.OFPFC_DELETE) ? 0 : (short) (1 << 0)); // OFPFF_SEND_FLOW_REM

        // set the ofp_action_header/out actions:
        // from the openflow 1.0 spec: need to set these on a struct ofp_action_output:
        // uint16_t type; /* OFPAT_OUTPUT. */
        // uint16_t len; /* Length is 8. */
        // uint16_t port; /* Output port. */
        // uint16_t max_len; /* Max length to send to controller. */
        // type/len are set because it is OFActionOutput,
        // and port, max_len are arguments to this constructor
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
    private void writePacketOutForPacketIn(IOFSwitch sw, 
                                          OFPacketIn packetInMessage, 
                                          short egressPort) {
        // from openflow 1.0 spec - need to set these on a struct ofp_packet_out:
        // uint32_t buffer_id; /* ID assigned by datapath (-1 if none). */
        // uint16_t in_port; /* Packet's input port (OFPP_NONE if none). */
        // uint16_t actions_len; /* Size of action array in bytes. */
        // struct ofp_action_header actions[0]; /* Actions. */
        /* uint8_t data[0]; */ /* Packet data. The length is inferred
                                  from the length field in the header.
                                  (Only meaningful if buffer_id == -1.) */
        
        OFPacketOut packetOutMessage = (OFPacketOut) floodlightProvider.getOFMessageFactory().getMessage(OFType.PACKET_OUT);
        short packetOutLength = (short)OFPacketOut.MINIMUM_LENGTH; // starting length
//find mac address
        OFMatch match = new OFMatch();
        match.loadFromPacket(packetInMessage.getPacketData(), packetInMessage.getInPort());
        Long destinationMac = Ethernet.toLong(match.getDataLayerDestination());
        // Set buffer_id, in_port, actions_len
        packetOutMessage.setBufferId(packetInMessage.getBufferId());
        packetOutMessage.setInPort(packetInMessage.getInPort());
      //  packetOutMessage.setActionsLength((short)OFActionOutput.MINIMUM_LENGTH);
       // packetOutLength += OFActionOutput.MINIMUM_LENGTH;
        
        // set actions
        List<OFAction> actions = new ArrayList<OFAction>();     
        System.out.println("Setting Actions");
        //short[] floodport;
        if(count==4)
        {	int c=0;
        	System.out.println("count====4");
        	 for(java.util.Map.Entry<Long, Short> entry: macvlan.entrySet())
         	{
        		 if(destinationMac==entry.getKey().longValue())
        		 {
        			 System.out.println("comparing"+destinationMac+"and"+entry.getKey());
		         		System.out.println("mac"+entry.getKey().toString());
		         		for(java.util.Map.Entry<Short, Short> entry1: vlanport.entrySet())
		         				{
		             				
		         					if(entry.getValue().shortValue()==entry1.getKey().shortValue())
		         					{
		         						
		         						
		         						System.out.println("Inserted ######333"+"entry.getValue()==entry1.getKey()"+entry.getValue()+" "+entry1.getKey());
		         						actions.add(new OFActionOutput(entry1.getValue(), (short) 0));
		         						c++;
		         					}
		         					else
		         					{
		         						System.out.println("Different vlan");
		         						return ;
		         						
		         					}
		         					
		             				System.out.println("port"+entry1.getValue());	
		         				}
        		 }
        		 else
        		 {
        			 System.out.println("returning coz no match");
        			 return;
        		 }
        		 
        		 
         	}
        	 if (c==0)
        	 {
        
        			 actions.add(new OFActionOutput((short)0, (short) 0));
        			 c=1;
           	 }
        	 System.out.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
             packetOutMessage.setActions(actions);
             packetOutMessage.setActionsLength((short) (c*OFActionOutput.MINIMUM_LENGTH));
             
             
        }
        else if(count<4)
        {
        	System.out.println("returning count<4");
        	return;
        }
        /*else
        {
        actions.add(new OFActionOutput(egressPort, (short) 0));
        
        packetOutMessage.setActions(actions);
        packetOutMessage.setActionsLength((short) OFActionOutput.MINIMUM_LENGTH);
        }*/
        byte[] packetData=null;
        // set data - only if buffer_id == -1
        if (packetInMessage.getBufferId() == OFPacketOut.BUFFER_ID_NONE) {
        	System.out.println("sending packetout");
             packetData = packetInMessage.getPacketData();
            packetOutMessage.setPacketData(packetData); 
            packetOutLength += (short)packetData.length;
        }
       // packetOutLength +=actions.size();
        // finally, set the total length
        System.out.println("packet length");
        System.out.println("Packet outlength"+OFPacketOut.MINIMUM_LENGTH+"Action length:"+ packetOutMessage.getActionsLength());
        packetOutMessage.setLength((short)(packetOutLength+ packetOutMessage.getActionsLength()));              
            
        // and write it out
        try {
        	counterStore.updatePktOutFMCounterStore(sw, packetOutMessage);
            sw.write(packetOutMessage, null);
        } catch (IOException e) {
            log.error("Failed to write {} to switch {}: {}", new Object[]{ packetOutMessage, sw, e });
        }
    }
    
    /**
     * Processes a OFPacketIn message. If the switch has learned the MAC/VLAN to port mapping
     * for the pair it will write a FlowMod for. If the mapping has not been learned the 
     * we will flood the packet.
     * @param sw
     * @param pi
     * @param cntx
     * @return
     */
    /*void createslice()
    {
    StaticFlowEntryPusher pusher = null;
	List<OFAction> actions = new ArrayList<OFAction>();
	// Declare the flow
	OFFlowMod fm = new OFFlowMod();
	// Declare the action
	OFActionVirtualLanIdentifier setvlanid = new OFActionVirtualLanIdentifier();
	setvlanid.setVirtualLanIdentifier((short) 10); //insert the vlan you'd like to set here
	actions.add((OFAction) setvlanid);
	
	// Declare the match
	OFMatch m = new OFMatch();
	m.setInputPort((short) 0 ); // insert the input port you'd like here
	// Assign the actions and match to the flow
	fm.setActions(actions);
	fm.setMatch(m);
	
	flow_0 = {
    	    'switch':"00:00:00:00:00:00:00:01",
    	    "name":"Flow_flow01",
    	    "cookie":"0",
    	    "priority":"32768",
    	    "ingress-port":"1",
    	    "active":"true",
    	    "actions":"set-vlan-id=10,output=3"
    	    }
	// push the flow
	pusher.addFlow("flow_0", fm, "00:00:00:00:00:00:00:01");
    
    
    }*/
    
    
    private Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
    	
    	
        // Read in packet data headers by using OFMatch
        OFMatch match = new OFMatch();
        match.loadFromPacket(pi.getPacketData(), pi.getInPort());
        Long sourceMac = Ethernet.toLong(match.getDataLayerSource());
        Long destMac = Ethernet.toLong(match.getDataLayerDestination());
        Short vlan = match.getDataLayerVirtualLan();
        if ((destMac & 0xfffffffffff0L) == 0x0180c2000000L) {
            if (log.isTraceEnabled()) {
                log.trace("ignoring packet addressed to 802.1D/Q reserved addr: switch {} vlan {} dest MAC {}",
                          new Object[]{ sw, vlan, HexString.toHexString(destMac) });
            }
            return Command.STOP;
        }
        
        if ((sourceMac & 0x010000000000L) == 0) {
            // If source MAC is a unicast address, learn the port for this MAC/VLAN
        	System.out.println("sourceMac"+sourceMac);
        	Short temp_val;
        	temp_val=vlan;
        	Short temp_port=null;
        	temp_port=this.checkPortMap(sw, sourceMac);
        
        	if(temp_port==null)
        	{
        		if(flag==true)
        		{
        			temp_val=(short)(temp_val+10);
        		this.addToPortMap(sw, sourceMac, temp_val, pi.getInPort());
        		macvlan.put(sourceMac,(Short)temp_val );
        		vlanport.put(temp_val, pi.getInPort());
        		flag=false;
        		}
        		
        		else{
        			flag=true;
        			temp_val=(short) (temp_val+20);
            		this.addToPortMap(sw, sourceMac, temp_val, pi.getInPort());
            		macvlan.put(sourceMac,(Short)temp_val );
            		vlanport.put(temp_val, pi.getInPort());
        		}
        	}
        }
        
        for(java.util.Map.Entry<IOFSwitch, Map<MacVlanPair, Short>> entry: macVlanToSwitchPortMap.entrySet())
    	{
    		System.out.println("Switch"+entry.getKey().toString());
    		for(java.util.Map.Entry<MacVlanPair, Short> entry1: entry.getValue().entrySet())
    				{
        			count++;
    					MacVlanPair g=entry1.getKey();
    					System.out.println("mac"+g.getMac());
    					System.out.println("vlan"+g.getVlan());	
        				System.out.println("port"+entry1.getValue());	
    				}
    	}
        if(count!=4)
        	count=0;
        
        // Now output flow-mod and/or packet
        Short outPort = getFromPortMap(sw, sourceMac,destMac);
        System.out.println("getFromPortMap---------outPort"+outPort);
        if (outPort == null) {
            // If we haven't learned the port for the dest MAC/VLAN, flood it
            // Don't flood broadcast packets if the broadcast is disabled.
            // XXX For LearningSwitch this doesn't do much. The sourceMac is removed
            //     from port map whenever a flow expires, so you would still see
            //     a lot of floods.
            this.writePacketOutForPacketIn(sw, pi, OFPort.OFPP_FLOOD.getValue());
        } else if (outPort == match.getInputPort()) {
            log.trace("ignoring packet that arrived on same port as learned destination:"
                    + " switch {} vlan {} dest MAC {} port {}",
                    new Object[]{ sw, vlan, HexString.toHexString(destMac), outPort });
        } else if(outPort==255){
        	System.out.println("Different vlan");
        	outPort=(short)0;
        	 //this.writePacketOutForPacketIn(sw, pi, OFPort.OFPP_FLOOD.getValue());
        	  this.writeFlowMod(sw, OFFlowMod.OFPFC_ADD, pi.getBufferId(), match,outPort );
        	return Command.CONTINUE;
        
    	}else {
            // Add flow table entry matching source MAC, dest MAC, VLAN and input port
            // that sends to the port we previously learned for the dest MAC/VLAN.  Also
            // add a flow table entry with source and destination MACs reversed, and
            // input and output ports reversed.  When either entry expires due to idle
            // timeout, remove the other one.  This ensures that if a device moves to
            // a different port, a constant stream of packets headed to the device at
            // its former location does not keep the stale entry alive forever.
            // FIXME: current HP switches ignore DL_SRC and DL_DST fields, so we have to match on
            // NW_SRC and NW_DST as well
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
        return Command.CONTINUE;
    }

    private Short checkPortMap(IOFSwitch sw, Long sourceMac) {
    	short vlan=0;
    	if (vlan == (short) 0xffff) {
            vlan = 0;
        }
        Map<MacVlanPair,Short> swMap = macVlanToSwitchPortMap.get(sw);
        if (swMap != null)
        {
  
        	for (java.util.Map.Entry<MacVlanPair, Short> entry : swMap.entrySet())
            {
            	/*System.out.println("Mac"+entry.getKey().mac.longValue());
        		System.out.println("Vlan"+entry.getKey().vlan.longValue());
        		System.out.println("Port"+entry.getValue());*/
            	if(entry.getKey().mac.longValue()==sourceMac)
            	{
            		System.out.println("Valid");
            		System.out.println(entry.getValue());
            		return entry.getValue().shortValue();
            	}
            	
            }
          
        	
        }
        
        
        // if none found
        return null;
		
	}

	/**
     * Processes a flow removed message. We will delete the learned MAC/VLAN mapping from
     * the switch's table.
     * @param sw The switch that sent the flow removed message.
     * @param flowRemovedMessage The flow removed message.
     * @return Whether to continue processing this message or stop.
     */
    private Command processFlowRemovedMessage(IOFSwitch sw, OFFlowRemoved flowRemovedMessage) {
        if (flowRemovedMessage.getCookie() != MyLearningSwitch.LEARNING_SWITCH_COOKIE) {
            return Command.CONTINUE;
        }
        if (log.isTraceEnabled()) {
            log.trace("{} flow entry removed {}", sw, flowRemovedMessage);
        }
        OFMatch match = flowRemovedMessage.getMatch();
        // When a flow entry expires, it means the device with the matching source
        // MAC address and VLAN either stopped sending packets or moved to a different
        // port.  If the device moved, we can't know where it went until it sends
        // another packet, allowing us to re-learn its port.  Meanwhile we remove
        // it from the macVlanToPortMap to revert to flooding packets to this device.
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
            	break;
        }
        log.error("received an unexpected message {} from switch {}", msg, sw);
        return Command.CONTINUE;
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
        macVlanToSwitchPortMap = 
                new ConcurrentHashMap<IOFSwitch, Map<MacVlanPair,Short>>();
        floodlightProvider =
                context.getServiceImpl(IFloodlightProviderService.class);
        counterStore =
                context.getServiceImpl(ICounterStoreService.class);
        restApi =
                context.getServiceImpl(IRestApiService.class);
        macvlan=new ConcurrentHashMap<Long,Short>();
        vlanport=new ConcurrentHashMap<Short,Short>();
    }

    @Override
    public void startUp(FloodlightModuleContext context) {
        floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
        floodlightProvider.addOFMessageListener(OFType.FLOW_REMOVED, this);
        floodlightProvider.addOFMessageListener(OFType.ERROR, this);
        restApi.addRestletRoutable(new LearningSwitchWebRoutable());
    }
    
    
    
}
