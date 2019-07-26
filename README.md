## A Java implementation of Node2vec

This Java implementation of Node2vec allows provision to selectively filter nodes appearing in the context of a node (as a part of random walk) based on additional constraints.
The additional constraint for now is a partition of nodes into communities as per any standard community detecting algorithm.

A sample graph (network) is provided. I also provided a sample partition of the network which is taken as input by the node embedding script.
Simply execute the script
```
sh node2vec-comm.sh
```  
