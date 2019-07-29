## A Java implementation of Node2vec

This Java implementation of Node2vec allows provision to selectively filter nodes appearing in the context of a node (as a part of random walk) based on additional constraints.
The additional constraint for now is a partition of nodes into communities as per any standard community detecting algorithm.

The program uses the open-sourced NMI evaluation package [GenConvNMI] (https://github.com/eXascaleInfolab/GenConvNMI). After cloning the GenConvNMI repository execute
```
make release
```
to build the necessary executables. This implementation contains a folder `clusteval` which has a snapshot of the GenConvNMI build for MAC. If you are using a different OS, you would need to freshly build the executables for the GenConvNMI package.  

A sample graph (network) is provided. I also provided a sample partition of the network which is taken as input by the node embedding script.
Simply execute the script *node2vec-comm.sh* with the following arguments

1. The graph file, which is an edge list file, where each line is of the format `<src-node> \t <target-node> \t <weight>` (if your graph is unweighted , specify 1 as weight).
2. The output file, where the program writes out the vector (in text format) for each node. The first token is the node name followed by the components of the vector for the node.
3. The ground-truth community for evaluation
4. The number of desired clusters for K-means clustering.
5. An optional partition file where each line is a space separated list of node names (matching the ones in the graph file corresponding to option 1).

A sample invokation (with no partition file specified) is
```
./node2vec-comm.sh data/LFR4000/network.txt ./data/LFR4000/vec_p.txt data/LFR4000/network_grdth_cmty_list.txt 50
```

Another invokation with the partition file is  

```
./node2vec-comm.sh data/LFR4000/network.txt ./data/LFR4000/vec_p.txt data/LFR4000/network_grdth_cmty_list.txt 50 data/LFR4000/modularity_based_cluster_output_LFR.txt
```
