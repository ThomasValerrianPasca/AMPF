import java.util.Arrays;
import java.util.Scanner;


public class Solution {

	public static void main(String[] args) {
		// TODO Auto-generated method stub
		int testcases=0;
		Scanner scan = new Scanner(System.in);
		testcases = scan.nextInt();
		for(int i=0;i<testcases;i++){
			int num=scan.nextInt();
			int mice[] = new int[num];
			int holes[] = new int[num];
			for(int j=0;j<num;j++){
				mice[j] = scan.nextInt();
			}
			for(int j=0;j<num;j++){
				holes[j]=scan.nextInt();
			}
			
			Arrays.sort(mice);
			Arrays.sort(holes);
			for(int j=0;j<num;j++){
				mice[j]=Math.abs(mice[j]-holes[j]);
			}
			int val=mice[0];
			for(int j=0;j<num;j++){
				if(val<mice[j]){
					val=mice[j];
				}
			}
			System.out.println(val);
		}
		

	}

}
