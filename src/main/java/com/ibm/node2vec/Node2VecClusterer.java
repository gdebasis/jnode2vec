/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.ibm.node2vec;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import org.apache.commons.math3.ml.clustering.CentroidCluster;

/**
 *
 * @author dganguly
 */
public class Node2VecClusterer {
    Properties prop;
    
    public Node2VecClusterer(String propFile) throws FileNotFoundException, IOException {
        prop = new Properties();
        prop.load(new FileReader(propFile));
    }
    
    public void cluster() throws IOException, Exception {
        WordVecs nodevecs = new WordVecs();
        nodevecs.loadFromTextFile(new FileInputStream(prop.getProperty("outfile")));
        
        int numClusters = Integer.parseInt(prop.getProperty("node2vec.numclusters"));
        
        System.out.println("Performing K-means clustering...");
        List<CentroidCluster<WordVec>> clusters = nodevecs.clusterWords(numClusters);
        
        System.out.println("Writing out clusters...");
        writeClusters(clusters);
    }
    
    public void writeClusters(List<CentroidCluster<WordVec>> clusters) throws IOException {
        int i = 1;
        String clustOutFileName = prop.getProperty("node2vec.cluster.output");
        FileWriter fw = new FileWriter(clustOutFileName);
        BufferedWriter bw = new BufferedWriter(fw);
        
        for (CentroidCluster<WordVec> c : clusters) {
            List<WordVec> thisClusterPoints = c.getPoints();
            for (WordVec thisClusterPoint: thisClusterPoints) {
                bw.write(thisClusterPoint.word + " ");
            }
            i++;
            bw.newLine();
        }     
        bw.close(); fw.close();
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: Node2VecClusterer <properties file>");
            System.err.println("Using default properties file...");
            
            args = new String[1];
            args[0] = "node2vec-comm.properties";
        }
        
        try {
            Node2VecClusterer c = new Node2VecClusterer(args[0]);
            c.cluster();
        }
        catch (Exception ex) { ex.printStackTrace(); }
    }
}
