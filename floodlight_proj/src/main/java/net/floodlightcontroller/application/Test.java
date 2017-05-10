/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package net.floodlightcontroller.application;

import java.io.File;
import jsat.ARFFLoader;
import jsat.DataSet;
import jsat.classifiers.CategoricalResults;
import jsat.classifiers.ClassificationDataSet;
import jsat.classifiers.Classifier;
import jsat.classifiers.DataPoint;
import jsat.classifiers.trees.DecisionTree;

/**
 *
 * @author thomas
 */
public class Test {

public   Classifier classifier = new DecisionTree();
    /**
     * @param args the command line arguments
     */
    public void Form_decision_tree()
    {
      //  String nominalPath = "uci" + File.separator + "nominal" + File.separator;
        //System.out.println(nominalPath);
   //     System.out.println( FileSystems.getFileSystem("./iris.arff"));
        File file = new File("/home/thomas/Desktop/collection_80.arff");

        DataSet dataSet = ARFFLoader.loadArffFile(file);
        
        //We specify '0' as the class we would like to make the target class. 
        ClassificationDataSet cDataSet = new ClassificationDataSet(dataSet, 0);
        
        int errors = 0;
      
        classifier.trainC(cDataSet);
       
 

       
        
                for(int i = 0; i < dataSet.getSampleSize(); i++)
                {
                    DataPoint dataPoint = cDataSet.getDataPoint(i);//It is important not to mix these up, the class has been removed from data points in 'cDataSet' 
                    int truth = cDataSet.getDataPointCategory(i);//We can grab the true category from the data set
                    System.out.println("Data Point"+dataPoint);
                    //Categorical Results contains the probability estimates for each possible target class value. 
                    //Classifiers that do not support probability estimates will mark its prediction with total confidence. 
                    CategoricalResults predictionResults = classifier.classify(dataPoint);
                    int predicted = predictionResults.mostLikely();
                    if(predicted != truth)
                        errors++;
                    System.out.println( i + "| True Class: " + truth + ", Predicted: " + predicted + ", Confidence: " + predictionResults.getProb(predicted) );

                }
            
            
             
                   // DataPoint dataPoint;//cDataSet.getDataPoint(i);//It is important not to mix these up, the class has been removed from data points in 'cDataSet' 
                    //  int truth = cDataSet.getDataPointCategory(i);//We can grab the true category from the data set
                   // System.out.println("Data Point##########"+dataPoint1);
                    //Categorical Results contains the probability estimates for each possible target class value. 
                    //Classifiers that do not support probability estimates will mark its prediction with total confidence. 
              
        System.out.println(errors + " errors were made, " + 100.0*errors/dataSet.getSampleSize() + "% error rate" );

        
        
    }
    
    
 /*   int getpredictedclass( double[] inputvalues )
    {               
                    int numReal = 4;
                    DenseVector v = new DenseVector(numReal);
                    v.set(0, inputvalues[0]);
                    v.set(1, inputvalues[0]);
                    v.set(2, inputvalues[0]);
                    v.set(3, inputvalues[0]);
                    DataPoint dataPoint1= new DataPoint(v,null,null);
                    CategoricalResults predictionResults = classifier.classify(dataPoint1);
                    int predicted = predictionResults.mostLikely();
                    System.out.println("Predicted: " + predicted + ", Confidence: " + predictionResults.getProb(predicted) );
                    return predicted;
    }*/
    
    
    public static void main(String args[])
    {
        Test t=new Test();
        t.Form_decision_tree();
    }
}
