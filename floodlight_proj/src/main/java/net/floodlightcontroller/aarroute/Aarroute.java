package net.floodlightcontroller.aarroute;

import java.io.IOException;
import java.nio.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.util.U16;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sun.security.provider.certpath.Vertex;
import net.floodlightcontroller.application.*;
import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.cost.Cost;
import net.floodlightcontroller.cost.ICost;
import net.floodlightcontroller.counter.ICounterStoreService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.kshortestpath.Graph;
import net.floodlightcontroller.kshortestpath.YenTopKShortestPathsAlg;
import net.floodlightcontroller.kshortestpath.YenTopKShortestPathsAlgTest;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.linkdiscovery.LinkInfo;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.routing.ForwardingBase;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.topology.NodePortTuple;
import net.floodlightcontroller.util.OFMessageDamper;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module to perform round-robin load balancing.
 * 
 */
public class Aarroute implements IOFMessageListener, IFloodlightModule {

	public Applicationawareness awareness;

	protected static int OFMESSAGE_DAMPER_CAPACITY = 50000; // TODO: find sweet
															// spot
	// protected static int OFMESSAGE_DAMPER_TIMEOUT = 250; // ms
	protected static int OFMESSAGE_DAMPER_TIMEOUT = 250000; // ms
	// Interface to Floodlight core for interacting with connected switches
	protected IFloodlightProviderService floodlightProvider;

	// Interface to link discovery service
	protected ILinkDiscoveryService linkDiscoveryProvider;

	// Interface to device manager service
	protected IDeviceService deviceProvider;

	// Interface to the logging system
	protected static Logger logger;

	// Interface to Cost finding module

	protected ICost cost;

	// Map for every new destination
	public Map<Integer, ConcurrentHashMap<Long, Integer>> DestMac_visited_switces = new ConcurrentHashMap<Integer, ConcurrentHashMap<Long, Integer>>();

	// flow-mod - for use in the cookie
	public static final int AppAware_COOKIE = 20;

	// graph
	Graph graph = new Graph();

	// Cost
	// Cost costobject= new Cost();
	// logging function
	protected static Logger log = LoggerFactory.getLogger(Aarroute.class);
	// writing message in a switch
	protected OFMessageDamper messageDamper;
	// Counter store to count the packets

	protected ICounterStoreService counterStore;
	// TODO Create list of servers to which traffic should be balanced

	long past_time, present_time;
	// int predictedclass=0;
	Map<Link, Long> temp_link_cost;

	Map<packet_count, Map<Long, packet_interval>> pktcount_map = new ConcurrentHashMap<packet_count, Map<Long, packet_interval>>();
	Map<packet_count, Short> packet_to_path_map ;

	/**
	 * Provides an identifier for our OFMessage listener. Important to override!
	 * */
	@Override
	public String getName() {
		return Aarroute.class.getSimpleName();
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
		// Auto-generated method stub
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// Auto-generated method stub
		return null;
	}

	/**
	 * Tells the module loading system which modules we depend on. Important to
	 * override!
	 */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> floodlightService = new ArrayList<Class<? extends IFloodlightService>>();
		floodlightService.add(IFloodlightProviderService.class);
		floodlightService.add(ILinkDiscoveryService.class);
		floodlightService.add(IDeviceService.class);

		return floodlightService;
	}

	/**
	 * Loads dependencies and initializes data structures. Important to
	 * override!
	 */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider = context
				.getServiceImpl(IFloodlightProviderService.class);
		linkDiscoveryProvider = context
				.getServiceImpl(ILinkDiscoveryService.class);
		deviceProvider = context.getServiceImpl(IDeviceService.class);
		logger = LoggerFactory.getLogger(Aarroute.class);

		messageDamper = new OFMessageDamper(OFMESSAGE_DAMPER_CAPACITY,
				EnumSet.of(OFType.FLOW_MOD), OFMESSAGE_DAMPER_TIMEOUT);
		packet_to_path_map = new ConcurrentHashMap<packet_count, Short>();

	}

	/**
	 * Tells the Floodlight core we are interested in PACKET_IN messages.
	 * Important to override!
	 * */
	@Override
	public void startUp(FloodlightModuleContext context) {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		Map<Link, Long> temp_link_cost = new HashMap<Link, Long>();
		printDevices();
		
		// awareness= new Applicationawareness();
		// awareness.Form_decision_tree();
	}

	/**
	 * get_outgoing_port_list
	 */
	short[] get_outgoing_port_list(IOFSwitch sw, short inport,
			Map<Long, Integer> visited_switches) {
		short list_of_op_ports[] = new short[10];

		// Initialize all port value
		for (int i = 0; i < 10; i++) {
			list_of_op_ports[i] = (short) 0;
		}

		// get all links
		Map<Link, LinkInfo> links = linkDiscoveryProvider.getLinks();

		System.out.println("ISEmpty visited switch " + visited_switches.size());
		if (visited_switches.size() == 0) {
			list_of_op_ports[0] = OFPort.OFPP_FLOOD.getValue();
			System.out.println("list_of_op_ports" + list_of_op_ports[0]);
			visited_switches.put(sw.getId(), 1);
			return list_of_op_ports;
		} else {
			visited_switches.put(sw.getId(), 1);
			Iterator<OFPhysicalPort> it = sw.getPorts().iterator();
			boolean flag = false;
			int j = 0;
			while (it.hasNext()) {
				//
				short temp;
				temp = it.next().getPortNumber();
				if (temp >= 0) {
					list_of_op_ports[j] = temp;
				}
				System.out.println("Output ports" + list_of_op_ports[j]);
				j++;
			}
			j = 0;// reset index
			for (Link link : links.keySet()) {

				if (link.getSrc() == sw.getId()) {
					System.out.println("Next hop switches are " + link.getDst()
							+ "  Connected through " + link.getSrcPort());
					for (Long vs : visited_switches.keySet()) {
						System.out.println("visited switches" + vs);
						if (vs == link.getDst()) {
							flag = true;
							System.out.println("already visited switch");
							break;
						}

						// System.out.println("Output ports"+sw.getPorts().toString());
						// if(link.getDst()==)
					}
					if (flag == true) {
						// remove that port
						for (j = 0; j < 10; j++) {
							if (list_of_op_ports[j] == link.getSrcPort()) {
								list_of_op_ports[j] = 0;// reset loop creating
														// port to zero
							}
						}
						flag = false;
					}

				}
				// link.getSrc()
				// logger.debug("SrcSwitch="+link.getSrc()+" SrcPort="+link.getSrcPort()+", DstSwitch="+link.getDst()+", DstPort="+link.getDstPort());
				// list_of_op_ports[0]=1;
			}
			Arrays.sort(list_of_op_ports);
			for (int number : list_of_op_ports) {
				System.out.println("port sort value" + number);
			}

			if (visited_switches.size() >=10) {
				System.out.println("reached");
				visited_switches.clear();

			}
			return list_of_op_ports;
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
        	//counterStore.updatePktOutFMCounterStore(sw, packetOutMessage);
            sw.write(packetOutMessage, null);
        } catch (IOException e) {
            log.error("Failed to write {} to switch {}: {}", new Object[]{ packetOutMessage, sw, e });
        }
    }

	/**
	 * Writes an OFPacketOut message to a switch.
	 * 
	 * @param sw
	 *            The switch to write the PacketOut to.
	 * @param packetInMessage
	 *            The corresponding PacketIn.
	 * @param egressPort
	 *            The switchport to output the PacketOut.
	 */
	private void writePacketOutForPacketIn(IOFSwitch sw,
			OFPacketIn packetInMessage, short egressPort,
			Map<Long, Integer> visited_switches) {
		// from openflow 1.0 spec - need to set these on a struct
		// ofp_packet_out:
		// uint32_t buffer_id; /* ID assigned by datapath (-1 if none). */
		// uint16_t in_port; /* Packet's input port (OFPP_NONE if none). */
		// uint16_t actions_len; /* Size of action array in bytes. */
		// struct ofp_action_header actions[0]; /* Actions. */
		/* uint8_t data[0]; *//*
							 * Packet data. The length is inferred from the
							 * length field in the header. (Only meaningful if
							 * buffer_id == -1.)
							 */

		short outgoing_port_list[];
		System.out.println("flood outputport" + OFPort.OFPP_FLOOD.getValue());
		// if(true);//to set flooding ports
		// sw.getPorts();//get all port of a switch
		// flood only to selected ports
		// check for minimum spanning tree
		OFPacketOut packetOutMessage = (OFPacketOut) floodlightProvider
				.getOFMessageFactory().getMessage(OFType.PACKET_OUT);
		short packetOutLength = (short) OFPacketOut.MINIMUM_LENGTH; // starting
																	// length

		// Set buffer_id, in_port, actions_len
		packetOutMessage.setBufferId(packetInMessage.getBufferId());
		packetOutMessage.setInPort(packetInMessage.getInPort());

		// set actions
		List<OFAction> actions = new ArrayList<OFAction>();
		// actions.add(new OFActionOutput(egressPort, (short) 0));
		// add all the other outgoing ports to action
		outgoing_port_list = get_outgoing_port_list(sw,
				packetInMessage.getInPort(), visited_switches);
		System.out.println("=============OFActionOutput.MINIMUM_LENGTH*"
				+ OFActionOutput.MINIMUM_LENGTH);
		for (int i = 0; i < 10; i++) {
			if (outgoing_port_list[i] != 0) {
				actions.add(new OFActionOutput((short) outgoing_port_list[i],
						(short) 0));
				System.out.println("Action outgoing ports"
						+ outgoing_port_list[i]);
			}
		}
		// System.out.println("actions.size()"+);
		// System.out.println("OFActionOutput.MINIMUM_LENGTH"+OFActionOutput.MINIMUM_LENGTH);

		packetOutMessage
				.setActionsLength((short) (OFActionOutput.MINIMUM_LENGTH * actions
						.size()));
		packetOutLength += OFActionOutput.MINIMUM_LENGTH * actions.size();

		packetOutMessage.setActions(actions);

		// packetOutLength +=actions.size();
		// set data - only if buffer_id == -1
		if (packetInMessage.getBufferId() == OFPacketOut.BUFFER_ID_NONE) {
			byte[] packetData = packetInMessage.getPacketData();
			packetOutMessage.setPacketData(packetData);
			packetOutLength += (short) packetData.length;
		}
		System.out.println("*=*=*=");

		// finally, set the total length
		packetOutMessage.setLength(packetOutLength);

		// and write it out
		try {
			// counterStore.updatePktOutFMCounterStore(sw, packetOutMessage);
			sw.write(packetOutMessage, null);
		} catch (IOException e) {
			log.error("Failed to write {} to switch {}: {}", new Object[] {
					packetOutMessage, sw, e });
		}
	}

	/**
	 * Receives an OpenFlow message from the Floodlight core and initiates the
	 * appropriate control logic. Important to override!
	 */
	@Override
	public net.floodlightcontroller.core.IListener.Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		
		// We only care about packet-in messages
		if (msg.getType() != OFType.PACKET_IN) {
			// Allow the next module to also process this OpenFlow message
			System.out.println("MSG is not PACKET_IN");
			return Command.CONTINUE;
		}
		OFPacketIn pi = (OFPacketIn) msg;
		System.out.println("started aaroutte and sw id is " + sw.getId()+" "+pi.getInPort());
		// System.out.println("packet in type"+pi.getDataAsString(sw, msg,
		// cntx));

		// Parse the received packet
		OFMatch match = new OFMatch();
		match.loadFromPacket(pi.getPacketData(), pi.getInPort());
		System.out.println("Actual Length" + msg.getData(sw, msg, cntx).length);
		// System.out.println("dl type is "+match.getDataLayerType() +
		// "ARP TYPE is "+Ethernet.TYPE_ARP);
		// We only care about IP packets
		if (match.getDataLayerType() != Ethernet.TYPE_IPv4) {
			System.out.println("not ethernet -- ARP");
			System.out.println("Data Layer Type" + match.getDataLayerType());
			printDevices();

			// =======================================================================

			// =======================================================================

			String destination_mac = find_device_with_ip(match
					.getNetworkDestination());
			if (!DestMac_visited_switces.containsKey(match
					.getNetworkDestination())) {
				// List of switches visited
				ConcurrentHashMap<Long, Integer> visited_switches;
				visited_switches = new ConcurrentHashMap<Long, Integer>();
				System.out.println("network dest is : "
						+ match.getNetworkDestination());
				DestMac_visited_switces.put(match.getNetworkDestination(),
						visited_switches);
			}

			if (destination_mac.equalsIgnoreCase("nomatch") == true) {
				System.out.println("flooding the packet");
				this.writePacketOutForPacketIn(sw, pi, OFPort.OFPP_FLOOD
						.getValue(), DestMac_visited_switces.get(match
						.getNetworkDestination()));
			} else {
				boolean device_found = routeFlow(sw, pi, cntx);
				if (!device_found) {
					System.out.println("Device not found");
					this.writePacketOutForPacketIn(sw, pi, OFPort.OFPP_FLOOD
							.getValue(), DestMac_visited_switces.get(match
							.getNetworkDestination()));
				}
			}

			return Command.CONTINUE;
		}

		int packet_length = msg.getLengthU();
		packet_count p_key = new packet_count();
		p_key.nw_destination = match.getNetworkDestination();
		p_key.nw_source = match.getNetworkSource();
		p_key.tp_destination = match.getTransportDestination();
		p_key.tp_source = match.getTransportSource();
		System.out.println("key nw dst" + p_key.nw_destination + "key nw src"
				+ p_key.nw_source + "Key tp dst" + p_key.tp_destination
				+ "Key tp src" + p_key.tp_source);
		Map<Long, packet_interval> interval_map;
		logger.debug("Received an IPv4 packet");

		System.out.println("Status" + pktcount_map.containsKey(p_key));

		if (pktcount_map.size() > 0) {
			Set<Entry<packet_count, Map<Long, packet_interval>>> e = pktcount_map
					.entrySet();
			Iterator it = e.iterator();
			while (it.hasNext()) {

				Entry<packet_count, Map<Long, packet_interval>> iters = (Entry<packet_count, Map<Long, packet_interval>>) it
						.next();
				System.out.println("N/W Src" + iters.getKey().nw_source);
				System.out.println("N/W Dst" + iters.getKey().nw_destination);
				System.out.println("TP Src" + iters.getKey().tp_source);
				System.out.println("TP Dst" + iters.getKey().tp_destination);
				Map<Long, packet_interval> temp = iters.getValue();

				for (Map.Entry<Long, packet_interval> p : temp.entrySet()) {
					// Entry<Long, packet_interval> p=(Entry<Long,
					// packet_interval>) temp.next();
					System.out.println("Entry Count" + p.getKey());
					System.out.println("Count" + p.getValue().count);
					System.out.println("inter_pkt_delay"
							+ p.getValue().inter_pkt_delay);
					System.out.println("received_timestamp"
							+ p.getValue().received_timestamp);

				}
			}
			System.out.println("size of map " + pktcount_map.size());
			// Iterator<Entry<packet_count, Map<Long, packet_interval>>> it=
			// temp_link_cost.entrySet().iterator();
			// while(it.hasNext())
			// {
			// @SuppressWarnings("unchecked")
			// Entry<Link,Long> o=(Entry<Link, Long>) it.next();
			// System.out.println("Source Switch "+o.getKey().getSrc()+", Source Port "+o.getKey().getSrcPort()+", Destination switch "+o.getKey().getDst()+", Destination Port "+o.getKey().getDstPort());
			// System.out.println("Latency "+o.getValue());
			// }
		}
		boolean found = false;
		Iterator<packet_count> pkt_itr = pktcount_map.keySet().iterator();
		packet_count temp_pkt_count = null;
		while (pkt_itr.hasNext()) {
			temp_pkt_count = pkt_itr.next();
			if ((temp_pkt_count.nw_destination == p_key.nw_destination)
					&& (temp_pkt_count.nw_source == p_key.nw_source)
					&& (temp_pkt_count.tp_destination == p_key.tp_destination)
					&& (temp_pkt_count.tp_source == p_key.tp_source)) {
				found = true;
				System.out.println("Packet Match found");
				break;
			}
		}
		
		
		
		
		
		if (found) {
			System.out.println("Packet Match found");
			System.out.println("New Flow has arrived at : "
					+ System.currentTimeMillis() + "source IP "
					+ IPv4.fromIPv4Address(p_key.nw_source) + " Dest IP " + IPv4.fromIPv4Address(p_key.nw_destination) 
					+ " dest port " + p_key.tp_destination + " Source Port "
					+ p_key.tp_source);
			if (pktcount_map.get(temp_pkt_count).size() <= 10) {

				interval_map = pktcount_map.get(temp_pkt_count);
				long index_value_of_map = interval_map.size();
				//routeFlow1(sw, pi, cntx);
				packet_interval tmp_pkt_interval = interval_map
						.get(index_value_of_map);

				index_value_of_map++;

				packet_interval pkt_intval = new packet_interval();
				pkt_intval.count = tmp_pkt_interval.count + 1;
				pkt_intval.inter_pkt_delay = System.currentTimeMillis()
						- tmp_pkt_interval.received_timestamp;
				pkt_intval.received_timestamp = System.currentTimeMillis();
				interval_map.put(index_value_of_map, pkt_intval);
				System.out.println("Number of packets queued: "
						+ index_value_of_map);
				this.writePacketOutForPacketIn(sw, pi, packet_to_path_map.get(p_key));
				
				return Command.STOP;
			}else{
				//pktcount_map.remove(p_key);
				//packet_to_path_map.remove(p_key);
				System.out.println("match.getinp "+match.getInputPort()+" out "+packet_to_path_map.get(p_key));
				this.writeFlowMod(sw, OFFlowMod.OFPFC_ADD, -1, match, packet_to_path_map.get(p_key));
				return Command.STOP;
				//routeFlow(sw, pi, cntx);
			} /*else {
				if (pktcount_map.get(temp_pkt_count).size() == 3) {
					System.out.println("Computing class");
					Map<Long, packet_interval> class_finder = pktcount_map
							.get(temp_pkt_count);
					long avg_inter_pkt_delay = 0, avg_pkt_length = 0;
					for (Map.Entry<Long, packet_interval> class_it : class_finder
							.entrySet()) {
						avg_inter_pkt_delay += class_it.getValue().inter_pkt_delay;
						avg_pkt_length += class_it.getValue().packet_size;
					}

					avg_inter_pkt_delay = avg_inter_pkt_delay / 50;
					avg_pkt_length = avg_pkt_length / 50;
					System.out.println("avg_inter_pkt_delay"
							+ avg_inter_pkt_delay);
					System.out.println("avg_pkt_length" + avg_pkt_length);
					double parameters[] = new double[6];
					// parameters[0]=temp_pkt_count.tp_destination;
					parameters[0] = temp_pkt_count.tp_destination;
					parameters[1] = avg_inter_pkt_delay;
					parameters[2] = avg_pkt_length;
					parameters[3] = temp_pkt_count.tp_source;
					parameters[4] = temp_pkt_count.nw_source;
					parameters[5] = temp_pkt_count.nw_destination;

					// predictedclass=awareness.getpredictedclass(parameters);
					// System.out.println("PredictedClass : "+(predictedclass+1)+
					// "  and destination port:"+temp_pkt_count.tp_destination);
				}

				// else go for packet processing

			}*/

		} else {
			printDevices();
			//check for device attachment if not attached then flood the packet
			System.out.println("Packet not found");
			interval_map = new HashMap<Long, packet_interval>();
			packet_interval pkt_interval = new packet_interval();
			pkt_interval.count = 0;
			pkt_interval.inter_pkt_delay = 0;
			pkt_interval.received_timestamp = System.currentTimeMillis();
			pkt_interval.packet_size = packet_length;
			interval_map.put((long) 1, pkt_interval);
			pktcount_map.put(p_key, interval_map);
			
			packet_length = 0;
			System.out.println("New Flow has arrived at : "
					+ System.currentTimeMillis() + "source IP "
					+ IPv4.fromIPv4Address(p_key.nw_source) + " Dest IP " + IPv4.fromIPv4Address(p_key.nw_destination) 
					+ " dest port " + p_key.tp_destination + " Source Port "
					+ p_key.tp_source);
			/*if(findDeviceAttachment(Ethernet.toLong(match.getDataLayerDestination())) == null){
				return Command.STOP;
			}*/
			routeFlow1(sw, pi, cntx, p_key);
			
			return Command.STOP;
		}

		/*System.out.println("Sending first packet out");

		long dl_dst = Ethernet.toLong(match.getDataLayerDestination());
		System.out.println("Destination mac" + dl_dst);
		// System.out.println("Device attached to switch "+findDeviceAttachment(dl_dst).getSwitchDPID()+" to port "+findDeviceAttachment(dl_dst).getPort());
		System.out.println("finding all paths^^^^^");
		routeFlow(sw, pi, cntx);
		// pushRoute(sw, pi,(short)1,cntx);

		return Command.CONTINUE;*/
	}

	/**
	 * Sends a packet out to the switch
	 */
	/*
	 * private void pushPacket(IOFSwitch sw, OFPacketIn pi,ArrayList<OFAction>
	 * actions, short actionsLength) {
	 * 
	 * // create an OFPacketOut for the pushed packet OFPacketOut po =
	 * (OFPacketOut) floodlightProvider.getOFMessageFactory()
	 * .getMessage(OFType.PACKET_OUT);
	 * 
	 * // Update the inputPort and bufferID po.setInPort(pi.getInPort());
	 * po.setBufferId(pi.getBufferId());
	 * 
	 * // Set the actions to apply for this packet po.setActions(actions);
	 * po.setActionsLength(actionsLength);
	 * 
	 * // Set data if it is included in the packet in but buffer id is NONE if
	 * (pi.getBufferId() == OFPacketOut.BUFFER_ID_NONE) { byte[] packetData =
	 * pi.getPacketData(); po.setLength(U16.t(OFPacketOut.MINIMUM_LENGTH +
	 * po.getActionsLength() + packetData.length));
	 * po.setPacketData(packetData); } else {
	 * po.setLength(U16.t(OFPacketOut.MINIMUM_LENGTH + po.getActionsLength()));
	 * }
	 * 
	 * logger.debug("Push packet to switch: "+po);
	 * 
	 * // Push the packet to the switch try { sw.write(po, null); } catch
	 * (IOException e) { logger.error("failed to write packetOut: ", e); } }
	 */
	/**
	 * Finds the switch and port to which the destination device is connected.
	 */
	private SwitchPort findDeviceAttachment(long mac) {
		// Find device based on MAC address address
		Iterator<? extends IDevice> deviceIterator = deviceProvider
				.queryDevices(mac, null, null, null, null);

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
	 * Finds the switch and port to which the destination device is connected.
	 */
	/*
	 * private SwitchPort findDeviceAttachmentfrom_ip(Integer destination_ip) {
	 * // Find device based on MAC address address Iterator<? extends IDevice>
	 * deviceIterator = deviceProvider.queryDevices(null, null, destination_ip,
	 * null, null);
	 * 
	 * // Select first matching device if (deviceIterator.hasNext()) { IDevice
	 * device = deviceIterator.next();
	 * 
	 * // Get device attachment points SwitchPort[] deviceSwitchPorts =
	 * device.getAttachmentPoints();
	 * 
	 * // Select first matching attachment point if (deviceSwitchPorts.length >=
	 * 1) { return deviceSwitchPorts[0]; } } return null; }
	 */

	/**
	 * Find a particular devices IP in the network.
	 */
	private String find_device_with_ip(int dest_ip) {
		String nomatch = "nomatch";
		// System.out.println("FINDING DEVICE WITH IP"+IPv4.fromIPv4Address(dest_ip));
		Collection<? extends IDevice> devices = deviceProvider.getAllDevices();
		Integer[] a;
		for (IDevice device : devices) {

			a = device.getIPv4Addresses();
			// System.out.println("IP array int= "+a);
			//System.out.println("Known IP array = " + IPv4.fromIPv4Address(a[0]));
			if (a.length > 0) {
				if (dest_ip == a[0]) {
					// System.out.println(" Found Destination MAC= "+device.getMACAddressString()
					// + "for IP= "+IPv4.fromIPv4Address(dest_ip));
					return device.getMACAddressString();
				}
			}
		}
		// System.out.println("IP int= "+dest_ip);
		System.out.println(" Returning no match for destination ip");
		return nomatch;
	}

	/**
	 * Print a list of all devices in the network.
	 */
	private void printDevices() {
		System.out.println("=================PRINT DEVICES===============");
		Collection<? extends IDevice> devices = deviceProvider.getAllDevices();
		for (IDevice device : devices) {
			System.out.print("MAC= " + device.getMACAddressString());
			// System.out.println("   IP= "+IPv4.fromIPv4Address(device.getIPv4Addresses()[0]));

		}
		System.out.println("=============================================");
	}

	/**
	 * Print a list of all switches in the network.
	 */
	private void printSwitches() {
		logger.debug("SWITCHES");
		Map<Long, IOFSwitch> switches = floodlightProvider.getSwitches();
		for (IOFSwitch sw : switches.values()) {
			logger.debug("SwitchId=" + sw.getId());
		}
	}

	/*
	 * Prints all links and corresponding cost
	 */
	private void printCost() {
		System.out.println("printing cost");
		Map<Link, LinkInfo> links = linkDiscoveryProvider.getLinks();
		for (Link link : links.keySet()) {
			System.out.println("Print Cost \nSrcSwitch=" + link.getSrc()
					+ " SrcPort=" + link.getSrcPort() + ", DstSwitch="
					+ link.getDst() + ", DstPort=" + link.getDstPort());
		}
		Iterator<Entry<Link, Long>> it = temp_link_cost.entrySet().iterator();
		while (it.hasNext()) {
			@SuppressWarnings("unchecked")
			Entry<Link, Long> o = (Entry<Link, Long>) it.next();
			System.out.println("Source Switch " + o.getKey().getSrc()
					+ ", Source Port " + o.getKey().getSrcPort()
					+ ", Destination switch " + o.getKey().getDst()
					+ ", Destination Port " + o.getKey().getDstPort());
			System.out.println("Latency " + o.getValue());
		}
	}

	/**
	 * Print a list of all links in the network
	 */
	private void printLinks() {
		logger.debug("LINKS");
		Map<Link, LinkInfo> links = linkDiscoveryProvider.getLinks();
		for (Link link : links.keySet()) {
			logger.debug("SrcSwitch=" + link.getSrc() + " SrcPort="
					+ link.getSrcPort() + ", DstSwitch=" + link.getDst()
					+ ", DstPort=" + link.getDstPort());
		}
	}

	/**
	 * Performs routing based on a packet-in OpenFlow message for an IPv4
	 * packet.
	 */
	private boolean routeFlow1(IOFSwitch inSwitch, OFPacketIn pi,
			FloodlightContext cntx, packet_count pkt) {
		// Create match based on packet
		OFMatch match = new OFMatch();
		match.loadFromPacket(pi.getPacketData(), pi.getInPort());

		// Get destination MAC and destination IP address
		long dl_dst = Ethernet.toLong(match.getDataLayerDestination());
		
		System.out.println(pi.getInPort());

		// Find switch and port to which device is connected
		SwitchPort deviceAttachment = findDeviceAttachment(dl_dst);
		if (null == deviceAttachment) {
			logger.error("Device attachement is unknown");
			return false;
		}

		present_time = System.currentTimeMillis() / 1000;

		System.out.println("present_time" + present_time);
		// Get the cost of Graph once in a while

		// if(past_time-present_time>10)
		// {
		System.out.println("Updating Graph");
		// temp_link_cost.clear();

		temp_link_cost = linkDiscoveryProvider.getLatency();

		System.out.println("Got all values");
		// Printing cost values

		printCost();
		past_time = System.currentTimeMillis() / 1000;
		System.out.println("past_time" + past_time);
		// Form a graph based on the cost and link values obtained

		// Reset Graph class
		graph.clear();
		// Iterate through the cost map and insert it into graph
		Map<Link, LinkInfo> links = linkDiscoveryProvider.getLinks();
		for (Link link : links.keySet()) {
			// System.out.println("Inserting in graph \nSrcSwitch="+link.getSrc()+" SrcPort="+link.getSrcPort()+", DstSwitch="+link.getDst()+", DstPort="+link.getDstPort());
		}
		/*
		 * Iterator<Entry<Link,Long>> it= temp_link_cost.entrySet().iterator();
		 * graph.setnumberofvertices(4); while(it.hasNext()) {
		 * 
		 * @SuppressWarnings("unchecked") Entry<Link,Long> o=(Entry<Link, Long>)
		 * it.next();
		 * System.out.println("Inserting Source Switch "+o.getKey().getSrc
		 * ()+", Source Port "
		 * +o.getKey().getSrcPort()+", Destination switch "+o.
		 * getKey().getDst()+", Destination Port "+o.getKey().getDstPort());
		 * System.out.println("Latency "+o.getValue());
		 * System.out.println("=====Inserting=======");
		 * 
		 * graph.setlink((int)o.getKey().getSrc(),(int)
		 * o.getKey().getDst(),o.getValue()); }
		 */
		long lat;
		graph.setnumberofvertices(8);
		for (Link link : links.keySet()) {
			System.out.println("Inserting in graph \nSrcSwitch="
					+ link.getSrc() + " SrcPort=" + link.getSrcPort()
					+ ", DstSwitch=" + link.getDst() + ", DstPort="
					+ link.getDstPort());

			if (temp_link_cost.containsKey(link))
				lat = temp_link_cost.get(link);

			else
				lat = 45;

			System.out.println("Latency " + lat);
			System.out.println("=====Inserting=======");
			graph.setlink((int) link.getSrc(), (int) link.getDst(), lat);
		}

		// }
		// Find The class of traffic
		/*
		 * int predictedclass=0; double parameters[]= new double[12];
		 * parameters[0]=10; parameters[1]=10; parameters[2]=11;
		 * parameters[3]=1643; parameters[4]=134; parameters[5]=143;
		 * parameters[6]=134; parameters[7]=1321; parameters[8]=143;
		 * parameters[9]=146; parameters[10]=186; parameters[11]=14;
		 * 
		 * awareness.getpredictedclass(parameters);
		 * System.out.println("PredictedClass : "+predictedclass);
		 */
		System.out.println("Find shortest path from" + (int) inSwitch.getId()
				+ " to " + (int) deviceAttachment.getSwitchDPID());

		YenTopKShortestPathsAlg k_paths;
		k_paths = find_k_shortestpath((int) inSwitch.getId(),
				(int) deviceAttachment.getSwitchDPID());
		System.out.println("protocol type" + match.getNetworkProtocol());
		short firstoutport = (short) 1;
		boolean one_packetout_flag = true;

		List<net.floodlightcontroller.kshortestpath.Path> p = k_paths
				.get_result_list();
		ListIterator<net.floodlightcontroller.kshortestpath.Path> path_iter = p
				.listIterator();

		while (path_iter.hasNext()) {
			/*
			 * if(match.getNetworkProtocol()==(byte)17) {
			 * System.out.println("Protocol Type is 17-- UDP");
			 * if(match.getTransportDestination()<5000) { //Class 1
			 * System.out.println("Sensitive Traffic--------< 5000 Class 1"); }
			 * else if(match.getTransportDestination()>5000
			 * &&match.getTransportDestination()<6000){ //Class 2
			 * System.out.println("Iterating"+ path_iter.nextIndex());
			 * path_iter.next(); System.out.println("Iterating"+
			 * path_iter.nextIndex());
			 * System.out.println("Sensitive Traffic--------> 5000 <6000 Class 2"
			 * ); } else if(match.getTransportDestination()>6000 &&
			 * match.getTransportDestination()<7000){ //Class 3
			 * System.out.println("Iterating"+ path_iter.nextIndex());
			 * path_iter.next(); System.out.println("Iterating"+
			 * path_iter.nextIndex()); System.out.println("Iterating"+
			 * path_iter.nextIndex()); if(path_iter.hasNext()) path_iter.next();
			 * System.out.println("Iterating"+ path_iter.nextIndex());
			 * System.out
			 * .println("Sensitive Traffic--------> 6000 <7000 Class 3"); }
			 * 
			 * else if(match.getTransportDestination()>7000 &&
			 * match.getTransportDestination()<8000){ //Class 4
			 * System.out.println("Iterating"+ path_iter.nextIndex());
			 * path_iter.next(); System.out.println("Iterating"+
			 * path_iter.nextIndex()); System.out.println("Iterating"+
			 * path_iter.nextIndex()); if(path_iter.hasNext()) path_iter.next();
			 * System.out.println("Iterating"+ path_iter.nextIndex());
			 * System.out.println("Iterating"+ path_iter.nextIndex());
			 * if(path_iter.hasNext()) path_iter.next();
			 * System.out.println("Iterating"+ path_iter.nextIndex());
			 * System.out
			 * .println("Sensitive Traffic--------> 7000 <8000 Class 4"); }
			 * 
			 * else{ System.out.println("Class 5");
			 * System.out.println("In Sensitive Traffic");
			 * while(path_iter.hasNext()) { System.out.println("Iterating"+
			 * path_iter.nextIndex()); path_iter.next();
			 * System.out.println("Iterating"+ path_iter.nextIndex());
			 * System.out.println("======================================"); }
			 * path_iter.previous(); }
			 * 
			 * }
			 */
			int rand = (int) (Math.random() * p.size());
			System.out.println("Rand Number " + rand);
			int i = 0;
			for (i = 0; i < rand; i++) {
				if (path_iter.hasNext()) {
					path_iter.next();
				} else {
					path_iter.previous();
					break;
				}
			}

			System.out.println("choosen path :" + i);

			net.floodlightcontroller.kshortestpath.Path path = path_iter.next();
			List<net.floodlightcontroller.kshortestpath.BaseVertex> vtx = path
					.get_vertices();
			Iterator<net.floodlightcontroller.kshortestpath.BaseVertex> vtx_iter = vtx
					.iterator();

			// iterates through all the switches available in that path
			boolean reverese_flow = true;
			if (match.getDataLayerType() != Ethernet.TYPE_IPv4) {
				reverese_flow = false;
			}
			System.out.println("Reverse Flow" + reverese_flow);
			short inport = 0, outport;
			int flag = 1;
			IOFSwitch last_switch = null;
			IOFSwitch sw = null;
			IOFSwitch sw1 = null;
			System.out.println("vertex set size is: "+vtx.size());
			while (vtx_iter.hasNext()) {
				if (flag == 1) {
					net.floodlightcontroller.kshortestpath.BaseVertex bv = vtx_iter
							.next();
					System.out.println("Base vertex" + bv.get_id());
					sw = getswitch_from_id(bv.get_id());
					last_switch = sw;

					net.floodlightcontroller.kshortestpath.BaseVertex bv1 = vtx_iter
							.next();
					System.out.println("Base vertex" + bv1.get_id());
					sw1 = getswitch_from_id(bv1.get_id());
					inport = pi.getInPort();
					flag = 0;

				} else {
					net.floodlightcontroller.kshortestpath.BaseVertex bv1 = vtx_iter
							.next();
					System.out.println("Base vertex" + bv1.get_id());
					sw1 = getswitch_from_id(bv1.get_id());

				}

				outport = getoutput_port(last_switch, sw1);

				if (sw == sw1) {
					System.out.println("sw == sw1");
					outport = (short) deviceAttachment.getPort();
					if (one_packetout_flag == true) {
						firstoutport = outport;
						one_packetout_flag = false;
					}
				}
				if (last_switch == sw1) {
					System.out.println("last_switch == sw1)");
					outport = (short) deviceAttachment.getPort();
				}
				// if node is not with in same switch
				if (one_packetout_flag == true) {
					firstoutport = outport;
					one_packetout_flag = false;
				}

				short next_inport = getinput_port(last_switch, sw1);
				System.out.println("Input port" + inport);
				System.out.println("Output port" + outport);
				System.out.println("In Switch" + last_switch.getId());
				
				/* update packet_to_path_map so that outport number can be used to send
				 * PACKET_OUT message
				 */
				if (last_switch == sw) { 
					System.out.println("First switch");
					if(packet_to_path_map.get(pkt)==null){
						packet_to_path_map.put(pkt, outport);
					}else{
						packet_to_path_map.remove(pkt);
						packet_to_path_map.put(pkt, outport);
					}
					System.out.println("packet_to_path_map.put(pkt, outport) : "+packet_to_path_map.put(pkt, outport));
					// for first switch insert only reverse path fwd path is used for collecting stats hence no need to insert rules till n packets
					OFMatch mat = match.clone();
					mat
					.setDataLayerSource(match.getDataLayerDestination())
                    .setDataLayerDestination(match.getDataLayerSource())
                    .setNetworkSource(match.getNetworkDestination())
                    .setNetworkDestination(match.getNetworkSource())
                    .setTransportSource(match.getTransportDestination())
                    .setTransportDestination(match.getTransportSource())
                    .setInputPort(outport);
				
					
					pushRoute(last_switch, pi, outport, inport, mat, cntx,
							false);
				}else{
					
					OFMatch mat = match.clone();
					mat
					.setDataLayerSource(match.getDataLayerSource())
                    .setDataLayerDestination(match.getDataLayerDestination())
                    .setNetworkSource(match.getNetworkSource())
                    .setNetworkDestination(match.getNetworkDestination())
                    .setTransportSource(match.getTransportSource())
                    .setTransportDestination(match.getTransportDestination())
                    .setInputPort(inport);
					
					pushRoute(last_switch, pi, inport, outport, mat, cntx,
						true);
				}
				inport = next_inport;
				//inport = getoutput_port(sw1, last_switch);
				last_switch = sw1;
				// for last switch rule
				System.out.println("!vtx_iter.hasNext()"
						+ (!vtx_iter.hasNext()));
				System.out.println("!(last_switch==sw1 || sw==sw1)"
						+ (!(last_switch == sw1 || sw == sw1)));
				System.out.println("last_switch==sw1 " + (last_switch == sw1)
						+ " sw==sw1" + (sw == sw1));

				if (!vtx_iter.hasNext() && (!(sw == sw1))) {
					outport = (short) deviceAttachment.getPort();
					match.setInputPort(inport);
					pushRoute(last_switch, pi, next_inport, outport, match,
							cntx, true);
					System.out.println("Input port" + next_inport);
					System.out.println("Output port" + outport);
					System.out.println("In Switch" + last_switch.getId());
				}
			}

			break;

		}
		System.out.println("firstoutport" + firstoutport);
		//pushPacket(inSwitch, match, pi, firstoutport, cntx);
		// write flow rule throughout the path
		/*
		 * {
		 * match.setWildcards(((Integer)sw.getAttribute(IOFSwitch.PROP_FASTWILDCARDS
		 * )).intValue() & ~OFMatch.OFPFW_IN_PORT & ~OFMatch.OFPFW_DL_TYPE &
		 * ~OFMatch.OFPFW_DL_VLAN & ~OFMatch.OFPFW_DL_SRC &
		 * ~OFMatch.OFPFW_DL_DST & ~OFMatch.OFPFW_NW_SRC_MASK &
		 * ~OFMatch.OFPFW_NW_DST_MASK); this.writeFlowMod(sw,
		 * OFFlowMod.OFPFC_ADD, pi.getBufferId(), match, outPort);}
		 */
		// floodlightProvider.

		Map<Long, Vertex> vertices = new HashMap<Long, Vertex>();

		// Consult the code in the printSwitches and printLinks functions
		// for examples of obtaining switches and links
		printSwitches();
		printLinks();
		printCost();
		// TODO: Determine the source and destination switches (or vertices) in
		// the network graph
		Vertex srcVertex; // Hint: Where was the packet received from?
		Vertex dstVertex; // Hint: Where is the host that the packet is destined
							// for?

		// TODO: Find the shortest path through the network from source to
		// destination

		// Consult the code in the example function in Dijkstra.java for an
		// example
		// on using Dijkstra's algorithm to find the shortest path through a
		// graph

		// TODO: Install flow rules in all switches along the path

		// TODO: Optional: Install reverse flow rules in all switches along the
		// path

		return true;
	}

	/**
	 * Performs routing based on a packet-in OpenFlow message for an IPv4
	 * packet.
	 */
	private boolean routeFlow(IOFSwitch inSwitch, OFPacketIn pi,
			FloodlightContext cntx) {
		// Create match based on packet
		OFMatch match = new OFMatch();
		match.loadFromPacket(pi.getPacketData(), pi.getInPort());

		// Get destination MAC and destination IP address
		long dl_dst = Ethernet.toLong(match.getDataLayerDestination());

		// Find switch and port to which device is connected
		SwitchPort deviceAttachment = findDeviceAttachment(dl_dst);
		if (null == deviceAttachment) {
			logger.error("Device attachement is unknown");
			return false;
		}

		present_time = System.currentTimeMillis() / 1000;

		System.out.println("present_time" + present_time);
		// Get the cost of Graph once in a while

		// if(past_time-present_time>10)
		// {
		System.out.println("Updating Graph");
		// temp_link_cost.clear();

		temp_link_cost = linkDiscoveryProvider.getLatency();

		System.out.println("Got all values");
		// Printing cost values

		printCost();
		past_time = System.currentTimeMillis() / 1000;
		System.out.println("past_time" + past_time);
		// Form a graph based on the cost and link values obtained

		// Reset Graph class
		graph.clear();
		// Iterate through the cost map and insert it into graph
		Map<Link, LinkInfo> links = linkDiscoveryProvider.getLinks();
		for (Link link : links.keySet()) {
			// System.out.println("Inserting in graph \nSrcSwitch="+link.getSrc()+" SrcPort="+link.getSrcPort()+", DstSwitch="+link.getDst()+", DstPort="+link.getDstPort());
		}

		long lat;
		graph.setnumberofvertices(8);
		for (Link link : links.keySet()) {
			System.out.println("Inserting in graph \nSrcSwitch="
					+ link.getSrc() + " SrcPort=" + link.getSrcPort()
					+ ", DstSwitch=" + link.getDst() + ", DstPort="
					+ link.getDstPort());

			if (temp_link_cost.containsKey(link))
				lat = temp_link_cost.get(link);

			else
				lat = 45;

			System.out.println("Latency " + lat);
			System.out.println("=====Inserting=======");
			int a,b;
			a=1;b=1;
			long cost_of_link=a*lat+b*(lat/576);
			System.out.println("cost of link is : "+lat+" "+" available bandwidth is "+ (576/lat) +" cost is "+cost_of_link);
			graph.setlink((int) link.getSrc(), (int) link.getDst(), cost_of_link);
		}

		// }
		// Find The class of traffic
		/*
		 * int predictedclass=0; double parameters[]= new double[12];
		 * parameters[0]=10; parameters[1]=10; parameters[2]=11;
		 * parameters[3]=1643; parameters[4]=134; parameters[5]=143;
		 * parameters[6]=134; parameters[7]=1321; parameters[8]=143;
		 * parameters[9]=146; parameters[10]=186; parameters[11]=14;
		 * 
		 * awareness.getpredictedclass(parameters);
		 * System.out.println("PredictedClass : "+predictedclass);
		 */
		System.out.println("Find shortest path from" + (int) inSwitch.getId()
				+ " to " + (int) deviceAttachment.getSwitchDPID());

		YenTopKShortestPathsAlg k_paths;
		k_paths = find_k_shortestpath((int) inSwitch.getId(),
				(int) deviceAttachment.getSwitchDPID());
		System.out.println("protocol type" + match.getNetworkProtocol());
		short firstoutport = (short) 1;
		boolean one_packetout_flag = true;

		List<net.floodlightcontroller.kshortestpath.Path> p = k_paths
				.get_result_list();
		ListIterator<net.floodlightcontroller.kshortestpath.Path> path_iter = p
				.listIterator();

		//if(path_iter.hasNext()){
			System.out.println("num of paths are : "+p.size());
		
		while (path_iter.hasNext()) {

			int rand = (int) (Math.random() * p.size());
			System.out.println("Rand Number " + rand);
			int i = 0;
			for (i = 0; i < rand; i++) {
				if (path_iter.hasNext()) {
					path_iter.next();
				} else {
					path_iter.previous();
					break;
				}
			}
			
			

			System.out.println("choosen path :" + i);

			net.floodlightcontroller.kshortestpath.Path path = path_iter.next();
			List<net.floodlightcontroller.kshortestpath.BaseVertex> vtx = path
					.get_vertices();
			Iterator<net.floodlightcontroller.kshortestpath.BaseVertex> vtx_iter = vtx
					.iterator();

			// iterates through all the switches available in that path
			boolean reverese_flow = true;
			if (match.getDataLayerType() != Ethernet.TYPE_IPv4) {
				reverese_flow = false;
			}
			System.out.println("Reverse Flow" + reverese_flow);
			short inport = 0, outport;
			int flag = 1;
			IOFSwitch last_switch = null;
			IOFSwitch sw = null;
			IOFSwitch sw1 = null;
			while (vtx_iter.hasNext()) {
				if (flag == 1) {
					net.floodlightcontroller.kshortestpath.BaseVertex bv = vtx_iter
							.next();
					System.out.println("Base vertex" + bv.get_id());
					sw = getswitch_from_id(bv.get_id());
					last_switch = sw;

					net.floodlightcontroller.kshortestpath.BaseVertex bv1 = vtx_iter
							.next();
					System.out.println("Base vertex" + bv1.get_id());
					sw1 = getswitch_from_id(bv1.get_id());
					inport = pi.getInPort();
					flag = 0;

				} else {
					net.floodlightcontroller.kshortestpath.BaseVertex bv1 = vtx_iter
							.next();
					System.out.println("Base vertex" + bv1.get_id());
					sw1 = getswitch_from_id(bv1.get_id());

				}

				outport = getoutput_port(last_switch, sw1);

				if (sw == sw1) {
					System.out.println("Sw is same as sw1");
					outport = (short) deviceAttachment.getPort();
					if (one_packetout_flag == true) {
						firstoutport = outport;
						one_packetout_flag = false;
					}
				}
				if (last_switch == sw1) {
					outport = (short) deviceAttachment.getPort();
				}
				// if node is not with in same switch
				if (one_packetout_flag == true) {
					firstoutport = outport;
					one_packetout_flag = false;
				}

				short next_inport = getinput_port(last_switch, sw1);
				System.out.println("Input port" + inport);
				System.out.println("Output port" + outport);
				System.out.println("In Switch" + last_switch.getId());
				match.setInputPort(inport);
				pushRoute(last_switch, pi, inport, outport, match, cntx,
						true);

				inport = next_inport;
				last_switch = sw1;
				// for last switch rule
				System.out.println("!vtx_iter.hasNext()"
						+ (!vtx_iter.hasNext()));
				System.out.println("!(last_switch==sw1 || sw==sw1)"
						+ (!(last_switch == sw1 || sw == sw1)));
				System.out.println("last_switch==sw1 " + (last_switch == sw1)
						+ " sw==sw1" + (sw == sw1));

				if (!vtx_iter.hasNext() && ((sw == sw1))) {
					outport = (short) deviceAttachment.getPort();
					match.setInputPort(inport);
					pushRoute(last_switch, pi, next_inport, outport, match,
							cntx, reverese_flow);
					System.out.println("Input port" + next_inport);
					System.out.println("Output port" + outport);
					System.out.println("In Switch" + last_switch.getId());
				}
			}

			break;

		}
		System.out.println("firstoutport" + firstoutport);
		pushPacket(inSwitch, match, pi, firstoutport, cntx);
		// write flow rule throughout the path
		/*
		 * {
		 * match.setWildcards(((Integer)sw.getAttribute(IOFSwitch.PROP_FASTWILDCARDS
		 * )).intValue() & ~OFMatch.OFPFW_IN_PORT & ~OFMatch.OFPFW_DL_TYPE &
		 * ~OFMatch.OFPFW_DL_VLAN & ~OFMatch.OFPFW_DL_SRC &
		 * ~OFMatch.OFPFW_DL_DST & ~OFMatch.OFPFW_NW_SRC_MASK &
		 * ~OFMatch.OFPFW_NW_DST_MASK); this.writeFlowMod(sw,
		 * OFFlowMod.OFPFC_ADD, pi.getBufferId(), match, outPort);}
		 */
		// floodlightProvider.

		Map<Long, Vertex> vertices = new HashMap<Long, Vertex>();

		// Consult the code in the printSwitches and printLinks functions
		// for examples of obtaining switches and links
		printSwitches();
		printLinks();
		printCost();
		// TODO: Determine the source and destination switches (or vertices) in
		// the network graph
		Vertex srcVertex; // Hint: Where was the packet received from?
		Vertex dstVertex; // Hint: Where is the host that the packet is destined
							// for?

		// TODO: Find the shortest path through the network from source to
		// destination

		// Consult the code in the example function in Dijkstra.java for an
		// example
		// on using Dijkstra's algorithm to find the shortest path through a
		// graph

		// TODO: Install flow rules in all switches along the path

		// TODO: Optional: Install reverse flow rules in all switches along the
		// path

		return true;
	}

	public short getoutput_port(IOFSwitch sw, IOFSwitch sw1) {
		Map<Link, LinkInfo> links = linkDiscoveryProvider.getLinks();
		for (Link link : links.keySet()) {
			if (link.getSrc() == sw.getId() && link.getDst() == sw1.getId()) {
				// System.out.println("Writing rule in  \nSrcSwitch="+link.getSrc()+" SrcPort="+link.getSrcPort()+", DstSwitch="+link.getDst()+", DstPort="+link.getDstPort());
				return link.getSrcPort();
			}

		}

		return 0;

	}

	public short getinput_port(IOFSwitch sw, IOFSwitch sw1) {
		Map<Link, LinkInfo> links = linkDiscoveryProvider.getLinks();
		if(sw.getId()==sw1.getId()){
			System.out.println("sw and sw1 are same");
		}
		for (Link link : links.keySet()) {
			if (link.getSrc() == sw.getId() && link.getDst() == sw1.getId()) {
				
				// System.out.println("Writing rule in  \nSrcSwitch="+link.getSrc()+" SrcPort="+link.getSrcPort()+", DstSwitch="+link.getDst()+", DstPort="+link.getDstPort());
				return link.getDstPort();
			}

		}

		return 0;

	}

	public IOFSwitch getswitch_from_id(long id) {
		IOFSwitch sw = null;
		Map<Long, IOFSwitch> switches = floodlightProvider.getSwitches();
		// getting IOFSwitch from ID
		for (IOFSwitch sw2 : switches.values()) {
			if (sw2.getId() == id) {
				System.out.println("SwitchId found as=" + sw2.getId());
				sw = sw2;
			}
		}
		return sw;
		// Done
	}

	public YenTopKShortestPathsAlg find_k_shortestpath(int src, int dest) {
		System.out.println("Finding k shortest path");
		System.out.println("========================");
		System.out.println("From Source:" + src + "to Destnation:" + dest);

		// Object s=graph.toString();
		// graph.export_to_file("test_graph.txt");

		YenTopKShortestPathsAlg yenAlg = new YenTopKShortestPathsAlg(graph,
				graph.get_vertex(src), graph.get_vertex(dest));
		int i = 0;
		while (yenAlg.has_next()) {
			System.out.println("Path " + i++ + " : " + yenAlg.next());
		}

		System.out.println("Result # :" + i);
		System.out.println("Candidate # :" + yenAlg.get_cadidate_size());
		System.out.println("All generated : "
				+ yenAlg.get_generated_path_size());
		/*
		 * YenTopKShortestPathsAlgTest alg = new YenTopKShortestPathsAlgTest();
		 * alg.testDijkstraShortestPathAlg(src,dest);
		 * alg.testYenShortestPathsAlg(src,dest);
		 */
		return yenAlg;

	}

	public boolean pushRoute(IOFSwitch sw, OFPacketIn pi, short inport,
			short outport, OFMatch match, FloodlightContext cntx,
			boolean reverese_flow) {
		

		System.out.println("pushing fwd route");
		Integer wildcard_hints;
		wildcard_hints = ((Integer) sw
				.getAttribute(IOFSwitch.PROP_FASTWILDCARDS)).intValue()
				& ~OFMatch.OFPFW_IN_PORT
				& ~OFMatch.OFPFW_NW_SRC_ALL
				& ~OFMatch.OFPFW_NW_DST_ALL
				& ~OFMatch.OFPFW_NW_DST_MASK
				& ~OFMatch.OFPFW_NW_SRC_MASK
				& ~OFMatch.OFPFW_DL_SRC
				& ~OFMatch.OFPFW_DL_DST
				& ~OFMatch.OFPFW_DL_TYPE
				& ~OFMatch.OFPFW_NW_PROTO & ~OFMatch.OFPFW_TP_DST;

		/*
		 * & ~OFMatch.OFPFW_DL_SRC & ~OFMatch.OFPFW_DL_DST &
		 * ~OFMatch.OFPFW_IN_PORT & ~OFMatch.OFPFW_DL_VLAN
		 */

		short FLOWMOD_DEFAULT_IDLE_TIMEOUT = 1000; // in seconds
		short FLOWMOD_DEFAULT_HARD_TIMEOUT = 1000; // infinite
		boolean srcSwitchIncluded = false;

		OFFlowMod fm = (OFFlowMod) floodlightProvider.getOFMessageFactory()
				.getMessage(OFType.FLOW_MOD);
		OFActionOutput action = new OFActionOutput();
		action.setMaxLength((short) 0xffff);
		action.setPort(outport);
		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(action);

		fm.setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
				.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT)
				.setBufferId(OFPacketOut.BUFFER_ID_NONE)
				.setCookie(Aarroute.AppAware_COOKIE)
				.setMatch(match)
				.setActions(actions)
				.setLengthU(
						OFFlowMod.MINIMUM_LENGTH
								+ OFActionOutput.MINIMUM_LENGTH);

		/*
		 * List<NodePortTuple> switchPortList = route.getPath();
		 * 
		 * for (int indx = switchPortList.size()-1; indx > 0; indx -= 2) { //
		 * indx and indx-1 will always have the same switch DPID. long
		 * switchDPID = switchPortList.get(indx).getNodeId(); IOFSwitch sw =
		 * floodlightProvider.getSwitches().get(switchDPID); if (sw == null) {
		 * if (log.isWarnEnabled()) {
		 * log.warn("Unable to push route, switch at DPID {} " +
		 * "not available", switchDPID); } return srcSwitchIncluded; }
		 */

		// set the match.
		fm.setMatch(wildcard(match, sw, wildcard_hints));
		/*
		 * // set buffer id if it is the source switch if (1 == indx) { // Set
		 * the flag to request flow-mod removal notifications only for the //
		 * source switch. The removal message is used to maintain the flow //
		 * cache. Don't set the flag for ARP messages - TODO generalize check if
		 * ((reqeustFlowRemovedNotifn) && (match.getDataLayerType() !=
		 * Ethernet.TYPE_ARP)) { fm.setFlags(OFFlowMod.OFPFF_SEND_FLOW_REM);
		 * match.setWildcards(fm.getMatch().getWildcards()); } }
		 */
		short outPort = outport;
		short inPort = inport;
		if (outPort == inPort || outPort == match.getInputPort()) {
			System.out.println("input and outpot port are same");
			System.out.println(Thread.currentThread().getStackTrace());
			StackTraceElement e[] = Thread.currentThread().getStackTrace();
			for(int i=0;i<e.length;i++){
				System.out.println(e[i].getClassName()+e[i].getLineNumber()+e[i].getMethodName());
			}
			return false;
		}
		// set input and output ports on the switch

		fm.getMatch().setInputPort(inPort);
		fm.setOutPort(outPort);
		((OFActionOutput) fm.getActions().get(0)).setPort(outPort);

		// counterStore.updatePktOutFMCounterStore(sw, fm);
		/*
		 * if (log.isTraceEnabled()) {
		 * log.trace("Pushing Route flowmod routeIndx={} " +
		 * "sw={} inPort={} outPort={}", new Object[] {indx, sw,
		 * fm.getMatch().getInputPort(), outPort });
		 */
		System.out.println("Pushing rule" + fm);

		try {
			sw.write(fm, cntx);
			/*
			 * if(reverese_flow) { OFFlowMod fm1 = (OFFlowMod)
			 * floodlightProvider.getOFMessageFactory()
			 * .getMessage(OFType.FLOW_MOD);
			 * 
			 * 
			 * }
			 */

			if (reverese_flow) {
				System.out.println("pushin rev route");
				this.writeFlowMod(sw, OFFlowMod.OFPFC_ADD, -1, match.clone()
						.setDataLayerSource(match.getDataLayerDestination())
						.setDataLayerDestination(match.getDataLayerSource())
						.setNetworkSource(match.getNetworkDestination())
						.setNetworkDestination(match.getNetworkSource())
						.setTransportSource(match.getTransportDestination())
						.setTransportDestination(match.getTransportSource())
						.setInputPort(outPort), match.getInputPort());
			}
			// messageDamper.write(sw, fm, cntx);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		sw.flush();

		// Push the packet out the source switch
		// if (sw.getId() == pinSwitch) {
		// TODO: Instead of doing a packetOut here we could also
		// send a flowMod with bufferId set....
		/*
		 * if(first_packetout) { pushPacket(sw, match, pi, outPort, cntx); }
		 */
		srcSwitchIncluded = true;
		// }

		return srcSwitchIncluded;
	}

	/**
	 * Writes a OFFlowMod to a switch.
	 * 
	 * @param sw
	 *            The switch tow rite the flowmod to.
	 * @param command
	 *            The FlowMod actions (add, delete, etc).
	 * @param bufferId
	 *            The buffer ID if the switch has buffered the packet.
	 * @param match
	 *            The OFMatch structure to write.
	 * @param outPort
	 *            The switch port to output it to.
	 */
	private void writeFlowMod(IOFSwitch sw, short command, int bufferId,
			OFMatch match, short outPort) {
		
		if ( outPort == match.getInputPort()) {
			System.out.println("input and outpot port are same");
			System.out.println(Thread.currentThread().getStackTrace());
			StackTraceElement e[] = Thread.currentThread().getStackTrace();
			for(int i=0;i<e.length;i++){
				System.out.println(e[i].getClassName()+e[i].getLineNumber()+e[i].getMethodName());
			}
			
		}
		short FLOWMOD_DEFAULT_IDLE_TIMEOUT = 1000; // in seconds
		short FLOWMOD_DEFAULT_HARD_TIMEOUT = 1000; // infinite

		OFFlowMod flowMod = (OFFlowMod) floodlightProvider
				.getOFMessageFactory().getMessage(OFType.FLOW_MOD);
		flowMod.setMatch(match);
		flowMod.setCookie(Aarroute.AppAware_COOKIE);
		flowMod.setCommand(command);
		flowMod.setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT);
		flowMod.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT);
		flowMod.setBufferId(bufferId);
		flowMod.setOutPort((command == OFFlowMod.OFPFC_DELETE) ? outPort
				: OFPort.OFPP_NONE.getValue());
		flowMod.setFlags((command == OFFlowMod.OFPFC_DELETE) ? 0
				: (short) (1 << 0)); // OFPFF_SEND_FLOW_REM

		flowMod.setActions(Arrays.asList((OFAction) new OFActionOutput(outPort,
				(short) 0xffff)));
		flowMod.setLength((short) (OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));
		try {
			
			sw.write(flowMod, null);
		} catch (IOException e) {
			log.error("Failed to write {} to switch {}", new Object[] {
					flowMod, sw }, e);
		}
	}

	protected OFMatch wildcard(OFMatch match, IOFSwitch sw,
			Integer wildcard_hints) {
		if (wildcard_hints != null) {
			return match.clone().setWildcards(wildcard_hints.intValue());
		}
		return match.clone();
	}

	/*
	 * public boolean pushRoute(Route route, OFMatch match, Integer
	 * wildcard_hints, OFPacketIn pi, long pinSwitch, long cookie,
	 * FloodlightContext cntx, boolean reqeustFlowRemovedNotifn, boolean
	 * doFlush, short flowModCommand) { short FLOWMOD_DEFAULT_IDLE_TIMEOUT = 5;
	 * // in seconds short FLOWMOD_DEFAULT_HARD_TIMEOUT = 0; // infinite boolean
	 * srcSwitchIncluded = false; OFFlowMod fm = (OFFlowMod)
	 * floodlightProvider.getOFMessageFactory() .getMessage(OFType.FLOW_MOD);
	 * OFActionOutput action = new OFActionOutput();
	 * action.setMaxLength((short)0xffff); List<OFAction> actions = new
	 * ArrayList<OFAction>(); actions.add(action);
	 * 
	 * fm.setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
	 * .setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT)
	 * .setBufferId(OFPacketOut.BUFFER_ID_NONE) .setCookie(cookie)
	 * .setCommand(flowModCommand) .setMatch(match) .setActions(actions)
	 * .setLengthU(OFFlowMod.MINIMUM_LENGTH+OFActionOutput.MINIMUM_LENGTH);
	 * 
	 * List<NodePortTuple> switchPortList = route.getPath();
	 * 
	 * for (int indx = switchPortList.size()-1; indx > 0; indx -= 2) { // indx
	 * and indx-1 will always have the same switch DPID. long switchDPID =
	 * switchPortList.get(indx).getNodeId(); IOFSwitch sw =
	 * floodlightProvider.getSwitches().get(switchDPID); if (sw == null) { if
	 * (log.isWarnEnabled()) {
	 * log.warn("Unable to push route, switch at DPID {} " + "not available",
	 * switchDPID); } return srcSwitchIncluded; }
	 * 
	 * // set the match. fm.setMatch(wildcard(match, sw, wildcard_hints));
	 * 
	 * // set buffer id if it is the source switch if (1 == indx) { // Set the
	 * flag to request flow-mod removal notifications only for the // source
	 * switch. The removal message is used to maintain the flow // cache. Don't
	 * set the flag for ARP messages - TODO generalize check if
	 * ((reqeustFlowRemovedNotifn) && (match.getDataLayerType() !=
	 * Ethernet.TYPE_ARP)) { fm.setFlags(OFFlowMod.OFPFF_SEND_FLOW_REM);
	 * match.setWildcards(fm.getMatch().getWildcards()); } }
	 * 
	 * short outPort = switchPortList.get(indx).getPortId(); short inPort =
	 * switchPortList.get(indx-1).getPortId(); // set input and output ports on
	 * the switch fm.getMatch().setInputPort(inPort);
	 * ((OFActionOutput)fm.getActions().get(0)).setPort(outPort);
	 * 
	 * try { counterStore.updatePktOutFMCounterStore(sw, fm); if
	 * (log.isTraceEnabled()) { log.trace("Pushing Route flowmod routeIndx={} "
	 * + "sw={} inPort={} outPort={}", new Object[] {indx, sw,
	 * fm.getMatch().getInputPort(), outPort }); } messageDamper.write(sw, fm,
	 * cntx); if (doFlush) { sw.flush(); }
	 * 
	 * // Push the packet out the source switch if (sw.getId() == pinSwitch) {
	 * // TODO: Instead of doing a packetOut here we could also // send a
	 * flowMod with bufferId set.... pushPacket(sw, match, pi, outPort, cntx);
	 * srcSwitchIncluded = true; } } catch (IOException e) {
	 * log.error("Failure writing flow mod", e); }
	 * 
	 * try { fm = fm.clone(); } catch (CloneNotSupportedException e) {
	 * log.error("Failure cloning flow mod", e); } }
	 * 
	 * return srcSwitchIncluded; } protected OFMatch wildcard(OFMatch match,
	 * IOFSwitch sw, Integer wildcard_hints) { if (wildcard_hints != null) {
	 * return match.clone().setWildcards(wildcard_hints.intValue()); } return
	 * match.clone(); }
	 */

	public void pushPacket(IOFSwitch sw, OFMatch match, OFPacketIn pi,
			short outport, FloodlightContext cntx) {
		System.out.println("pushing flows");
		if (pi == null) {
			return;
		} else if (pi.getInPort() == outport) {
			log.warn("Packet out not sent as the outport matches inport. {}",
					pi);
			return;
		}

		// The assumption here is (sw) is the switch that generated the
		// packet-in. If the input port is the same as output port, then
		// the packet-out should be ignored.
		if (pi.getInPort() == outport) {
			if (log.isDebugEnabled()) {
				log.debug("Attempting to do packet-out to the same "
						+ "interface as packet-in. Dropping packet. "
						+ " SrcSwitch={}, match = {}, pi={}", new Object[] {
						sw, match, pi });
				return;
			}
		}

		if (log.isTraceEnabled()) {
			log.trace("PacketOut srcSwitch={} match={} pi={}", new Object[] {
					sw, match, pi });
		}

		OFPacketOut po = (OFPacketOut) floodlightProvider.getOFMessageFactory()
				.getMessage(OFType.PACKET_OUT);

		// set actions
		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(new OFActionOutput(outport, (short) 0xffff));

		po.setActions(actions).setActionsLength(
				(short) OFActionOutput.MINIMUM_LENGTH);
		short poLength = (short) (po.getActionsLength() + OFPacketOut.MINIMUM_LENGTH);

		// If the switch doens't support buffering set the buffer id to be none
		// otherwise it'll be the the buffer id of the PacketIn
		if (sw.getBuffers() == 0) {
			// We set the PI buffer id here so we don't have to check again
			// below
			pi.setBufferId(OFPacketOut.BUFFER_ID_NONE);
			po.setBufferId(OFPacketOut.BUFFER_ID_NONE);
		} else {
			po.setBufferId(pi.getBufferId());
		}

		po.setInPort(pi.getInPort());

		// If the buffer id is none or the switch doesn's support buffering
		// we send the data with the packet out
		if (pi.getBufferId() == OFPacketOut.BUFFER_ID_NONE) {
			byte[] packetData = pi.getPacketData();
			poLength += packetData.length;
			po.setPacketData(packetData);
		}

		po.setLength(poLength);

		try {
			// counterStore.updatePktOutFMCounterStore(sw, po);
			System.out.println(po.getDataAsString(sw, po, cntx));
			// messageDamper.write(sw, po, cntx);
			sw.write(po, cntx);
		} catch (IOException e) {
			log.error("Failure writing packet out", e);
		}
	}

}
