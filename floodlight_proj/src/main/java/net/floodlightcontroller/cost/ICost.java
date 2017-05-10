package net.floodlightcontroller.cost;
import java.util.Map;

import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.routing.Link;

public interface ICost extends IFloodlightService{
	
	//Get Link and Corresponding Cost. Use always a regular update 
	//to have a new updated value of the cost in case of dynamic cost based routing 
	
	public Map<Link, Long> getlinkandcost();
	

}
