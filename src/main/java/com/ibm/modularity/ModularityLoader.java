/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.ibm.modularity;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;

/**
 * Loads a modularity output file, where each line is of the following format:
 * NODE 
 * @author dganguly
 */
public class ModularityLoader {
    String modularityOutputFile;
    Map<String, List<Integer>> nodeToCommunity;

    public ModularityLoader(String modularityOutputFile) throws Exception {
        this.modularityOutputFile = modularityOutputFile;
        
        List<String> communities = FileUtils.readLines(new File(modularityOutputFile), Charset.defaultCharset());
        
        nodeToCommunity = new HashMap<>();
        int communityIndex = 0;
        
        for (String community: communities) {
            String[] nodes = community.split("\\s+");
            for (String node: nodes) {
                List<Integer> assignedCommunitiesForThisNode = nodeToCommunity.get(node);
                
                if (assignedCommunitiesForThisNode == null) {
                    assignedCommunitiesForThisNode = new ArrayList<>();
                    nodeToCommunity.put(node, assignedCommunitiesForThisNode);
                }
                assignedCommunitiesForThisNode.add(communityIndex);
            }
            communityIndex++;
        }
    }

    // Takes as argument a key node id (current node in node2vec) and a
    // reference node id (one of the context nodes).
    // Retrieves the communities of both... returns true if there's an overlap
    // else false.
    public boolean overlapInCommunity(String pivotNode, String refNode) {
        List<Integer> pivotComm = nodeToCommunity.get(pivotNode);
        List<Integer> refComm = nodeToCommunity.get(refNode);
        
        if (pivotComm==null || refComm==null)
            return false; // intersection is null
        
        HashSet<Integer> pivotCommSet = new HashSet<>(pivotComm);
        pivotCommSet.retainAll(refComm);
        return !pivotComm.isEmpty();
    }
    
}
