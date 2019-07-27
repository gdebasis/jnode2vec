#!/bin/bash

if [ $# -lt 2 ]
then
	echo "usage: $0 <graph file> <outvec file> <partition file>"
	exit
fi

GRAPHFILE=$1
OUTFILE=$2
PARTITIONFILE=$3    

if [ $# -lt 3 ]
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

EOF1

fi

#make sure that we're working on the latest source
mvn compile

#Put trace=3 if you want verbose o/p 

mvn exec:java@node2vec -Dexec.args="-props node2vec-comm.properties -trace 2" 

