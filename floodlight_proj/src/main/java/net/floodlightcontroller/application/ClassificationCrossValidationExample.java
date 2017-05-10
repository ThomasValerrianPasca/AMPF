/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.floodlightcontroller.application;
import java.io.File;
import jsat.ARFFLoader;
import jsat.DataSet;
import jsat.classifiers.ClassificationDataSet;
import jsat.classifiers.ClassificationModelEvaluation;
import jsat.classifiers.Classifier;
import jsat.classifiers.bayesian.NaiveBayes;

/**
 * Testing data on the same data used to train is considered bad, and can overstate the true accuracy of a classifier. 
 * Cross Validation is a method to evaluate a model by cycling through the whole data set. While this takes more time,
 * it uses all the data for both training and testing, without ever testing a data point that was trained on. 
 * 
 * @author Edward Raff
 */
public class ClassificationCrossValidationExample
{

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args)
    {
       // String nominalPath = "uci" + File.separator + "nominal" + File.separator;
        File file = new File("/home/thomas/installation/iris.arff");
        DataSet dataSet = ARFFLoader.loadArffFile(file);
        
        //We specify '0' as the class we would like to make the target class. 
        ClassificationDataSet cDataSet = new ClassificationDataSet(dataSet, 0);

        //We do not train the classifier, we let the modelEvaluation do that for us!
        Classifier classifier = new NaiveBayes();
                
        ClassificationModelEvaluation modelEvaluation = new ClassificationModelEvaluation(classifier, cDataSet);
        
        //The number of folds is how many times the data set will be split and trained and tested. 10 is a common value
        modelEvaluation.evaluateCrossValidation(60);
        
        System.out.println("Cross Validation error rate is " + 100.0*modelEvaluation.getErrorRate() + "%");
        
        //We can also obtain how long it took to train, and how long classification took
        System.out.println("Trainig time: " + modelEvaluation.getTotalTrainingTime()/1000.0 + " seconds");
        System.out.println("Classification time: " + modelEvaluation.getTotalClassificationTime()/1000.0 + " seconds\n");
        
        //The model can print a 'Confusion Matrix' this tells us about the errors our classifier made. 
        //Each row represents all the data points that belong to a given class. 
        //Each column represents the predicted class
        //That means values in the diagonal indicate the number of correctly classifier points in each class. 
        //Off diagonal values indicate mistakes
        modelEvaluation.prettyPrintConfusionMatrix();
        
    }
}