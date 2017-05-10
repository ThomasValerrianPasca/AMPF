package nextGreaterString;

import java.util.Arrays;
import java.util.Scanner;

public class Main {
	public static void main(String args[]) {
		int testcases;
		Scanner scan = new Scanner(System.in);
		testcases = scan.nextInt();
		for (int i = 0; i < testcases; i++) {
			String str = scan.next();
			int j = str.length() - 1;
			for (; j > 0; j--) {
				if (str.charAt(j) > str.charAt(j - 1)) {
					break;
				}
			}
			if(j==0){
				System.out.println("no answer");
				continue;
			}
			char nextHighest=str.charAt(j-1);


			String substr = str.substring(j);

			char[] chars = substr.toCharArray();
			Arrays.sort(chars);
			
			int k=0;
			for(;k<chars.length;k++){
				if(nextHighest < chars[k]){
					char temp;
					temp=nextHighest;
					nextHighest=chars[k];
					chars[k]=temp;
					break;
				}
			}
			Arrays.sort(chars);
			String sorted = new String(chars);
			String ans = str.substring(0,j-1)+nextHighest+sorted;
			System.out.println(ans);

		}
	}
}
