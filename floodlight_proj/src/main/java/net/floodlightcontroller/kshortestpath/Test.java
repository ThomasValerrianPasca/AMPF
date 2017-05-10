package net.floodlightcontroller.kshortestpath;
import java.io.*;
public class Test{

	public static void main(String[] args) throws NumberFormatException, IOException{
		
		int source=0,destination=0;
		BufferedReader br= new BufferedReader(new InputStreamReader(System.in));
		System.out.println("Enter your Source ");
		source=Integer.parseInt(br.readLine());
		System.out.println("Enter your Destination ");
		destination=Integer.parseInt(br.readLine());
		find__paths(source,destination);
	//	YenTopKShortestPathsAlg alg = new YenTopKShortestPathsAlg();
	//	alg.testDijkstraShortestPathAlg();
		//alg.testYenShortestPathsAlg();
		
	}
	
	public static void find__paths(int src, int dest)
	{
		YenTopKShortestPathsAlgTest alg = new YenTopKShortestPathsAlgTest();
		System.out.println(src);
		System.out.println(dest);
		Graph graph= new Graph();
		graph.setnumberofvertices(5);
		graph.setlink(0 ,1, 19);
		graph.setlink(0 ,3 ,23);
		graph.setlink(1, 2 ,1);
		graph.setlink(2, 1 ,1);
		graph.setlink(1 ,3 ,3);
		graph.setlink(1, 4 ,2);
		graph.setlink(2, 4, 4);
		graph.setlink(3, 4 ,1);
			alg.testDijkstraShortestPathAlg(src,dest);
			alg.testYenShortestPathsAlg(src,dest);
	}

}
