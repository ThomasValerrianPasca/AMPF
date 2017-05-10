package net.floodlightcontroller.cost;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.learningswitch.*;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.TimerTask;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFStatisticsRequest;
import org.openflow.protocol.OFType;
import org.openflow.protocol.statistics.OFFlowStatisticsRequest;
import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.protocol.statistics.OFStatisticsType;
import net.floodlightcontroller.core.IFloodlightProviderService;

public class Request extends Thread{
	public IFloodlightProviderService floodlightProvider;
	public void run() {
		{
			System.out.println("Running=========================");
			long past=System.currentTimeMillis();
			//IOFMessageListener listener = new Cost();
			
			while(true)
			{	
			
				long present;
				
			present=System.currentTimeMillis();
			if(present-past>1000)
			{
			
			System.out.println("sent request successfully");
			past=present;
			System.out.println("Received Successfully 1");
			OFStatisticsRequest req = new OFStatisticsRequest();
			           req.setStatisticType(OFStatisticsType.FLOW);
			           int requestLength = req.getLengthU();
			           OFFlowStatisticsRequest specificReq = new OFFlowStatisticsRequest();
			               OFMatch match = new OFMatch();
			               match.setWildcards(0xffffffff);
			               specificReq.setMatch(match);
			               specificReq.setOutPort(OFPort.OFPP_NONE.getValue());
			               specificReq.setTableId((byte) 0xff);
			               System.out.println("Received Successfully 2");
			               req.setStatistics(Collections.singletonList((OFStatistics)specificReq));
			               requestLength += specificReq.getLength();
			               req.setLengthU(requestLength);
			               System.out.println("Received Successfully 3");
			               
						@SuppressWarnings("null")
						Map<Long,IOFSwitch> map =  floodlightProvider.getSwitches();
						System.out.println("Received Successfully 4");
			for (Map.Entry<Long,IOFSwitch> entry : map.entrySet()) {
			try {
				// Object learningSwitch ;
			System.out.println("floodlightProvider"+floodlightProvider);
			System.out.println("entry"+entry.getValue());
			System.out.println("req"+req.toString());
			System.out.println("req xid"+req.getXid());
			//floodlightProvider.addOFMessageListener(type, listener)
			//floodlightProvider.addOFMessageListener(OFType.PACKET_IN, (IOFMessageListener) this);
			entry.getValue().sendStatsQuery(req, req.getXid(),(IOFMessageListener)floodlightProvider.getListeners());
			}
			catch (IOException ex) {
			System.out.println("Exception : "+ex.getMessage());
			}
			}
			System.out.println("Received Successfully 5");
			}
		
		
			}
			
			
			
		}
		
		
		
	}

	


	public void start_query(IFloodlightProviderService floodlightProvider)
	{
		this.floodlightProvider=floodlightProvider;
		this.start();
		System.out.println("in start_query=========================");
		//run(floodlightProvider);
	}

	
	
	

	}
