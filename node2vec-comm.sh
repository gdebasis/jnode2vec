#!/bin/bash

if [ $# -lt 4 ]
then
	echo "usage: $0 <graph file> <outvec file> <ground-truth community> <num-clusters> <partition file>"
	exit
fi

GRAPHFILE=$1
OUTFILE=$2
GT=$3
NUMCLUSTERS=$4    
PARTITIONFILE=$5

if [ $# -lt 5 ]
then

cat > node2vec-comm.properties << EOF1
inode2vec.layer1_size=128
node2vec.window=20
node2vec.ns=20
node2vec.niters=10
node2vec.p1=0.5
node2vec.q1=0.5
graphfile=$GRAPHFILE
outfile=$OUTFILE
node2vec.numclusters=$NUMCLUSTERS
node2vec.cluster.output=$OUTFILE.kmeans.$NUMCLUSTERS

EOF1

else

cat > node2vec-comm.properties << EOF1
inode2vec.layer1_size=128
node2vec.window=20
node2vec.ns=20
node2vec.niters=10
node2vec.p1=0.5
node2vec.q1=0.5
partition.file=$PARTITIONFILE
graphfile=$GRAPHFILE
outfile=$OUTFILE
node2vec.numclusters=$NUMCLUSTERS
node2vec.cluster.output=$OUTFILE.kmeans.$NUMCLUSTERS

EOF1

fi

#Put trace=3 if you want verbose o/p 

mvn compile

mvn exec:java@node2vec -Dexec.args="-props node2vec-comm.properties -trace 2 -iter 10 -window 50" 

mvn exec:java@kmeans -Dexec.args="node2vec-comm.properties" 

GECMI=./clusteval/GenConvNMI-master/bin/Release/gecmi
$GECMI $GT $OUTFILE.kmeans.$NUMCLUSTERS 

