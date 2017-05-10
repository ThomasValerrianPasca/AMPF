package net.floodlightcontroller.applicationawareness;
import java.sql.Timestamp;

public class IpTcpTuple {
	public String srcIP, dstIP;
	public int dstPort,srcPort;
	
	public IpTcpTuple(String srcIP, String dstIP, int srcPort, int dstPort){
		this.srcIP=srcIP;
		this.dstIP=dstIP;
		this.srcPort=srcPort;
		this.dstPort=dstPort;
	}
	
	public boolean matchTuple(String srcIP, String dstIP, int srcPort, int dstPort){
		
		if(this.srcIP.equals(srcIP) && this.dstIP.equals(dstIP) && this.srcPort==srcPort && this.dstPort==dstPort){
			return true;
		}else{
			return false;
		}
	}
	
	public String data(){
		return srcIP +","+ dstIP +","+  srcPort +","+ dstPort;
	}

	@Override
	public boolean equals(Object obj) {
		//System.out.println("Entered equals object");
		IpTcpTuple temp = (IpTcpTuple)obj;
		
		if(matchTuple(temp.srcIP, temp.dstIP, temp.srcPort, temp.dstPort)){
			return true;
		}else{
			return false;
		}
		//return super.equals(obj);
	}

	@Override
	public int hashCode() {
		//System.out.println("hashcode");
		return data().hashCode();
		
	}

	
	/*public boolean equals(Object other){
		System.out.println("Entered equals object");
		IpTcpTuple temp = (IpTcpTuple)other;
		
		if(matchTuple(temp.srcIP, temp.dstIP, temp.srcPort, temp.dstPort)){
			return true;
		}else{
			return false;
		}
		
		
	}*/

}

class FlowStats{
	private Timestamp timeStamp[];
	private long utimeStamp[];
	private int Count;
	private int MAX_PKTS;
	
	public FlowStats(int n){
		timeStamp = new Timestamp[n];
		utimeStamp = new long[n];
		Count=0;
		MAX_PKTS=n;
	}
	
	public int getCount(){
		return Count;
	}
	
	/*public void incCount(){
		Count++;
	}*/
	
	public boolean checkCount(){
		//System.out.println("Entered checkcount object");
		return Count == MAX_PKTS ? true : false;
	}
	
	public Timestamp getTimeStamp(){
		return timeStamp[Count-1];
	}
	
	public long getUTimeStamp(){
		return utimeStamp[Count-1];
	}
	public long getTimeDifference(){
		if(Count>1)
			return utimeStamp[Count-1]-utimeStamp[Count-2];	
		else 
			return 0;
	}
	public void updateStas(){
		java.util.Date date= new java.util.Date();
		 //System.out.println(new Timestamp(date.getTime()));
		timeStamp[Count] = new Timestamp(date.getTime());
		utimeStamp[Count] = System.currentTimeMillis();
		Count++;
	}
}
