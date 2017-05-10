package net.floodlightcontroller.aarroute;


public class packet_count {
	int nw_destination;
	int nw_source;
	int tp_destination;
	int tp_source;
	
	@Override
	public boolean equals(Object obj) {
		//System.out.println("Entered equals object");
		packet_count temp = (packet_count)obj;
		
		if ((temp.nw_destination == this.nw_destination)
				&& (temp.nw_source == this.nw_source)
				&& (temp.tp_destination == this.tp_destination)
				&& (temp.tp_source == this.tp_source)) {
			return true;
		}
		else{
			return false;
		}
		//return super.equals(obj);
	}
	
	@Override
	public int hashCode() {
		//System.out.println("hashcode");
		return data().hashCode();
		
	}
	
	String data(){
		return "" + nw_destination + nw_source + tp_destination + tp_source;
	}
}
