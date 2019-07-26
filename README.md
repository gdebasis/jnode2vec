## A Java implementation of Node2vec

This Java implementation of Node2vec allows provision to selectively filter nodes appearing in the context of a node (as a part of random walk) based on additional constraints.
The additional constraint for now is a partition of nodes into communities as per any standard community detecting algorithm.

A sample graph (network) is provided. I also provided a sample partition of the network which is taken as input by the node embedding script.
Simply execute the script *node2vec-comm.sh* with the following arguments

1. The graph file, which is an edge list file, where each line is of the format `<src-node> \t <target-node> \t <weight>` (if your graph is unweighted , specify 1 as weight).
2. The output file, where the program writes out the vector (in text format) for each node. The first token is the node name followed by the components of the vector for the node.
3. An optional partition file where each line is a space separated list of node names (matching the ones in the graph file corresponding to option 1).

A sample invokation (with no partition file specified) is
```
sh node2vec-comm.sh data/LFR4000/network.txt data/LFR4000/vec.txt 
```

Another invokation with the partition file is  

```
sh node2vec-comm.sh data/LFR4000/network.txt data/LFR4000/vec_p.txt data/LFR4000/modularity_based_cluster_output_LFR.txt
```
