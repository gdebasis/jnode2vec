package com.ibm.node2vec;

import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import org.apache.commons.io.IOUtils;

import java.io.*;
import static java.lang.System.exit;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Properties;
import com.ibm.modularity.ModularityLoader;

/**
 * A Java port of node2vec.c
 *
 * node2vec is an algorithmic framework for representational learning on graphs.
 * Given any graph, it can learn continuous feature representations for the nodes,
 * which can then be used for various downstream machine learning tasks.
 *
 * Concretely, given a graph, node2vec produces vector representations for each node.
 *
 * // Copyright 2013 Google Inc. All Rights Reserved.
 * //
 * //  Licensed under the Apache License, Version 2.0 (the "License");
 * //  you may not use this file except in compliance with the License.
 * //  You may obtain a copy of the License at
 * //
 * //      http://www.apache.org/licenses/LICENSE-2.0
 * //
 * //  Unless required by applicable law or agreed to in writing, software
 * //  distributed under the License is distributed on an "AS IS" BASIS,
 * //  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * //  See the License for the specific language governing permissions and
 * //  limitations under the License.
 *
 * @author marting, debasis
 * */

/*
Translation notes:
Here is the type mapping I tried first:

typedef float real -> float
typedef char byte -> boolean if it represents a 1/0 (usually used for booleans)
char* -> String
anything* -> Anything or Anything[] (TODO: see if List<Anything> are better)


THERE IS A BUG in the C code: l 327-328
    should be if (!directed) then add the reverse edge

Confirmed that:
    RNG is preserved
    Word hash is preserved
    'float' should be equivalent in Java and C
 */

// Refer to the node2vec paper --- consider moving from t,v and then to x
enum VisitStatus {
    CASE_P,
    CASE_ONE,
    CASE_Q
}

public class Node2Vec {

    public static final int EXP_TABLE_SIZE = 1000;
    public static final int MAX_EXP = 6;
    public static final int MAX_LINE_SIZE = 10000;
    public static final int MAX_SENTENCE_LENGTH = 1000;
    public static final int MAX_CODE_LENGTH = 40;
    public static final int MAX_OUT_DEGREE = 5000;
    public static final int MAX_CONTEXT_PATH_LEN = 100;
    public static final int SEED = 123456;

    public static final int vocab_hash_size = 300000;  // Maximum 30 * 0.7 = 21M words in the vocabulary

    public static class edge {
        public vocab_node dest; //struct vocab_node* dest;
        public float weight;
        public boolean twohop; // 1 if two-hop;
    }

    edge[][] multiHopEdgeLists;
    float p1, q1;
    String partitionFile;
    ModularityLoader seedPartitions;

    // represents a node structure
    public static class vocab_node {
        public int id; // the id (hash index) of the word
        // TODO: char* originally, see if String fits
        public String word;
        //TODO: edge *edge_list;
        public edge[] edge_list = null;
        public int cn; // out degree
        public boolean visited;
    }

    InputStream train_file;
    OutputStream output_file, output_file_vec;
    vocab_node[] vocab;
    int debug_mode = 2; int window = 10; int min_count = 0;
    boolean pqsampling;
    int[] vocab_hash; //int *vocab_hash;
    int vocab_max_size = 1000; int vocab_size = 0; int layer1_size = 100;
    int train_nodes = 0; int iter = 5; boolean directed = true;
    float alpha = 0.025f;
    // TODO: last one might be a pointer to a cell in 'syn0' (in this case, turn to int)
    float[] syn0, syn1, syn1neg, expTable, pt_syn0;
    float onehop_pref = 0.7f;
    float one_minus_onehop_pref;
    int negative = 5;
    final int table_size = (int)1e8;
    int[] table;
    InputStream pretrained_file;
    
    // TODO: probably a pointer to a byte array
    //char* pt_word_buff;
    //int pt_word_buff;
    WordVecs ptWordVecs; // pre-trained word vectors

    Properties props;

    public Node2Vec() { props = new Properties(); }
    
    public Node2Vec(String propFile) throws IOException {
        props = new Properties();
        props.load(new FileReader(propFile));
        readParameters();
    }

    final void readParameters() throws FileNotFoundException {
        train_file = new FileInputStream(props.getProperty("graphfile"));
        output_file = new FileOutputStream(props.getProperty("outfile"));
        
        layer1_size = Integer.parseInt(props.getProperty("node2vec.layer1_size", "128"));
        onehop_pref = Float.parseFloat(props.getProperty("node2vec.onehop_pref", "0.7"));
        alpha = Float.parseFloat(props.getProperty("node2vec.alpha", "0.025"));
        directed = Boolean.parseBoolean(props.getProperty("node2vec.directed", "true"));
        window = Integer.parseInt(props.getProperty("node2vec.window", "5"));
        negative = Integer.parseInt(props.getProperty("node2vec.ns", "10"));
        iter = Integer.parseInt(props.getProperty("node2vec.niters", "10"));
        pqsampling = Boolean.parseBoolean(props.getProperty("node2vec.pqsampling", "true"));
        min_count = Integer.parseInt(props.getProperty("node2vec.mincount", "1"));
        p1 = Float.parseFloat(props.getProperty("node2vec.p1", "0.5"));
        q1 = Float.parseFloat(props.getProperty("node2vec.q1", "0.5"));
        debug_mode = Integer.parseInt(props.getProperty("trace", "3"));
    }
    
    final void loadFiles() {
        try {
            if (pretrained_file == null) {
                String ptFile = props.getProperty("node2vec.ptfile");
                if (ptFile != null)
                    pretrained_file = new FileInputStream(ptFile);
            }

            if (partitionFile == null)
                partitionFile = props.getProperty("partition.file");
            
            if (partitionFile != null) {
                seedPartitions = new ModularityLoader(partitionFile);
            }                
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
    public void setInput(InputStream input) {
        train_file = input;
    }

    public void setOutput(OutputStream output) {
        output_file = output;
    }

    void InitUnigramTable() {
        int a, i;
        int train_nodes_pow = 0;
        float d1;
        float power = 0.75f;
        table = new int[table_size];
        for (a = 0; a < vocab_size; a++) train_nodes_pow += Math.pow(vocab[a].cn, power);
        i = 0;
        d1 = (float)Math.pow(vocab[i].cn, power) / (float)train_nodes_pow;
        for (a = 0; a < table_size; a++) {
            table[a] = i;
            if (a / (float)table_size > d1) {
                i++;
                d1 += Math.pow(vocab[i].cn, power) / (float)train_nodes_pow;
            }
            if (i >= vocab_size) i = vocab_size - 1;
        }
    }

    // Reads the node id from a file... stops reading when it sees a tab character.
    // Each line in the graph file is: <src node>\t<dest-node>\t<weight>
    // marting: returns true when a line has been read, false if EOF
    boolean ReadSrcNode(BufferedReader fin) throws IOException {
        String node_id1, node_id2;
        float f;
        int a, i;

        String line = fin.readLine();
        if (line == null) return false;
        String[] split = line.split("\\t");
        assert split.length == 3 : "Wrong format, didn't match expected: <src node>\\t<dest-node>\\t<weight>";
        node_id1 = split[0];
        node_id2 = split[1];
        //f = Float.parseFloat(split[2]); // not used here
        String[] node_ids = { node_id1, node_id2 };
        for (String node_id : node_ids) {
            i = SearchVocab(node_id);
            if (i == -1) {
                a = AddWordToVocab(node_id);
                vocab[a].cn = 1;
            }
            else vocab[i].cn++;
        }
        return true;
    }

    // Returns hash value of a word (tested to be exactly the same as in C)
    int GetWordHash(String word) {
        UnsignedInteger hash = UnsignedInteger.valueOf(0);
        for (int a = 0; a < word.length(); a++) {
            hash = hash.times(UnsignedInteger.valueOf(257)).plus(UnsignedInteger.valueOf(word.charAt(a)));
        }
        hash = hash.mod(UnsignedInteger.valueOf(vocab_hash_size));
        return hash.intValue();
    }

    // Returns position of a word in the vocabulary; if the word is not found, returns -1
    int SearchVocab(String word) {
        int hash = GetWordHash(word);
        while (true) {
            if (vocab_hash[hash] == -1) return -1;
            if (word.equals(vocab[vocab_hash[hash]].word)) return vocab_hash[hash];
            hash = (hash + 1) % vocab_hash_size;
        }
    }

    // Adds a word to the vocabulary
    int AddWordToVocab(String word) {
        int hash, id;
        vocab[vocab_size] = new vocab_node();
        vocab[vocab_size].word = word;
        vocab[vocab_size].cn = 0;
        vocab[vocab_size].visited = false;

        vocab_size++;
        // Reallocate memory if needed
        if (vocab_size + 2 >= vocab_max_size) {
            vocab_max_size += 1000;
            vocab = Arrays.copyOf(vocab, vocab_max_size);
        }
        hash = GetWordHash(word);
        while (vocab_hash[hash] != -1) hash = (hash + 1) % vocab_hash_size;

        id = vocab_size - 1;
        vocab_hash[hash] = id;
        // vocab_size-1 is the index of the current word... save it in the node object
        vocab[id].id = id;
        //printf("\n%s Adding word",word);
        return id;
    }

    // Used later for sorting by out degrees
    int VocabCompare(vocab_node a, vocab_node b) {
        if (b.cn != a.cn)
            return b.cn - a.cn;
        else return a.word.compareTo(b.word);
    }

    // Sorts the vocabulary by frequency using word counts
    void SortVocab() {
        int a, size;
        int hash;
        // Sort the vocabulary and keep </s> at the first position
        // in C, qsort uses the count as second parameter, in Java, sort uses the upper bound (excluded)
        //qsort(&vocab[1], vocab_size - 1, sizeof(struct vocab_node), VocabCompare);
        Arrays.sort(vocab, 1, vocab_size, new Comparator<vocab_node>() {
            @Override
            public int compare(vocab_node o1, vocab_node o2) {
                return VocabCompare(o1, o2);
            }
        });
        for (a = 0; a < vocab_hash_size; a++) vocab_hash[a] = -1;
        size = vocab_size;
        train_nodes = 0;
        for (a = 0; a < size; a++) {
            // Nodes with out-degree less than min_count times will be discarded from the vocab
            if ((vocab[a].cn < min_count) && (a != 0)) {
                vocab_size--;
                vocab[a] = null;
            } else {
                // Hash will be re-computed, as after the sorting it is not actual
                hash=GetWordHash(vocab[a].word);
                while (vocab_hash[hash] != -1) hash = (hash + 1) % vocab_hash_size;
                vocab_hash[hash] = a;
                train_nodes += vocab[a].cn;
            }
        }
        vocab = Arrays.copyOf(vocab, vocab_size + 1);
    }

    // Stores a list of pointers to edges for each source node.
    // The overall list is thus a pointer to pointer to the lists.
    void initContexts() {
        // a list of contexts for each source node in the graph
        multiHopEdgeLists = new edge[vocab_size][];
        for (int i = 0; i < vocab_size; i++) {
            multiHopEdgeLists[i] = new edge[MAX_CONTEXT_PATH_LEN + 1]; // +1 for the NULL termination
        }
    }

    boolean addEdge(String src, String dest, float wt) {
        int src_node_index, dst_node_index, cn;
        edge[] edge_list;

        // Get src node id
        src_node_index = SearchVocab(src);
        if (src_node_index == -1) {
            System.out.println(String.format("Word '%s' OOV...", src));
            return false;
        }

        // Get dst node id
        dst_node_index = SearchVocab(dest);
        if (dst_node_index == -1) {
            System.out.println(String.format("Word '%s' OOV...", dest));
            return false;
        }

        // allocate edges
        edge_list = vocab[src_node_index].edge_list;
        if (edge_list == null) {
            edge_list = new edge[MAX_OUT_DEGREE];
            cn = 0;
        }
        else {
            cn = vocab[src_node_index].cn; // current number of edges
        }

        if (cn == MAX_OUT_DEGREE) {
            System.err.println("Can't add anymore edges...");
            return false;
        }
        edge_list[cn] = new edge();
        edge_list[cn].dest = vocab[dst_node_index];
        edge_list[cn].dest.id = dst_node_index;
        edge_list[cn].weight = wt;
        vocab[src_node_index].edge_list = edge_list;

        vocab[src_node_index].cn = cn + 1; // number of edges
        
        assert(vocab[src_node_index].edge_list != null);
        
        // +++DG: Test-case fix... Can't check this now... Have to defer it
        // in the calling function... currently, only checking for source not null
        //assert(vocab[dst_node_index].edge_list != null);
        // ---DG
        
        return true;
    }

    // Each line represents an edge...
    // format is <src-node-id> \t <dest-node-id> \t <weight>
    // supports the option of directed/undirected...
    // for undirected option, the function adds the reverse edges
    void constructGraph(BufferedReader fp) throws IOException {
        int i, count = 0;
        String src_word, dst_word, wt_word;
        float wt = 1;

        if (debug_mode > 2)
            System.out.println("Reading edges from each line...");
        String line;
        while ((line = fp.readLine()) != null) {

            String[] split = line.split("\\t");
            src_word = split[0];
            dst_word = split[1];
            
            if (split.length > 2) {
                wt_word = split[2];
                wt = Float.parseFloat(wt_word);
            }

            if (!addEdge(src_word, dst_word, wt))
                continue;  // add this edge to G

            if (!directed) {
                if (!addEdge(dst_word, src_word, wt))
                    continue;
            }

            count++;
            if (debug_mode > 3)
                System.out.println(String.format("Read line %d", count));
        }
    }

    // an important step is to normalize the edge weights to probabilities
    // of samples that would be used later on during sampling nodes
    // from this pre-built context.
    void preComputePathContextForSrcNode(int src_node_index) {
        int i = 0, j, num_one_hops;  // index into the context buffer
        edge q;
        edge[] multiHopEdgeList;
        vocab_node src_node;

        src_node = vocab[src_node_index];
        multiHopEdgeList = multiHopEdgeLists[src_node_index]; // write to the correct buffer

        // First, collect a set of one hop nodes from this source node
        for (int pIndex = 0; pIndex < src_node.cn; pIndex++) {
            edge p = src_node.edge_list[pIndex];
            // visit a one-hop node from source
            if (!p.dest.visited && i < MAX_CONTEXT_PATH_LEN) {
                multiHopEdgeList[i++] = p;
                p.twohop = false;
                p.dest.visited = true;
            }
        }
        num_one_hops = i;

        // iterate over the one hops collected to reach the 2 hops (that are not one-hop connections)
        for (j = 0; j < num_one_hops; j++) {
            q = multiHopEdgeList[j];
            if (!q.dest.visited && q.dest != src_node && i < MAX_CONTEXT_PATH_LEN) { // q->dest != src_node avoids cycles!
                multiHopEdgeList[i++] = q;
                q.twohop = true;
                q.dest.visited = true;
            }
        }

        // TODO: probably not required in Java
        multiHopEdgeList[i] = null;  // terminate with a NULL

        // reset the visited flags (for next call to the function)
        for (j = 0; j < i; j++) {
            multiHopEdgeList[j].weight *= multiHopEdgeList[j].twohop ? one_minus_onehop_pref : onehop_pref;  // prob of one-hop vs two-hop
            multiHopEdgeList[j].dest.visited = false;
        }
    }

    // Precompute the set of max-hop nodes for each source node.
    void preComputePathContexts() {
        initContexts();

        if (debug_mode > 2)
            System.out.println("Initialized contexts...");

        for (int i = 0; i < vocab_size; i++) {
            preComputePathContextForSrcNode(i);
            if (debug_mode > 3)
                System.out.println(String.format("Precomputed contexts for node %d (%s)", i, vocab[i].word));
        }
    }

    /** marting: factored this line here, was found several times in the original code */
    static UnsignedLong getNextRandom(UnsignedLong previousRandom) {
        return previousRandom.times(UnsignedLong.valueOf(25214903917L)).plus(UnsignedLong.valueOf(11L));
    }
    
    static float getNextRandomInOpenUnitInterval(UnsignedLong r) {
        r = getNextRandom(r); 
        return (r.bigIntegerValue().and(BigInteger.valueOf(0xFFFFL)).floatValue()) / (float)65536;
    }

    // Sample a context of size <window>
    // contextBuff is an o/p parameter
    int adjSampling(int src_node_index, UnsignedLong next_random, edge[] contextBuff) {
        edge[] multiHopEdgeList;
        int len = MAX_CONTEXT_PATH_LEN;
        float x, cumul_p, z, norm_wt;

        // see how many 2-hop adj neighbors we have got for this node

        multiHopEdgeList = multiHopEdgeLists[src_node_index]; // buffer to sample from
        int pIndex;
        for (pIndex = 0; pIndex < MAX_CONTEXT_PATH_LEN; pIndex++) {
            edge p = multiHopEdgeList[pIndex];
            if (p == null) break;
        }
        len = pIndex;
        if (debug_mode > 2)
            System.out.println(String.format("#nodes in 2-hop neighborhood = %d", len));

        len = Math.min(len, window); //len = window < len ? window : len;

        // TODO: I don't understand this memset here, we intend to fill the first 'window' cells of contextBuff
        // and 'window' is always >= 'len', so why bother with this memset?
        Arrays.fill(contextBuff, 0, len, null);
        //memset(contextBuff, 0, sizeof(edge*)*len);

        // normalize the weights so that they sum to 1;
        z = 0;
        for (int i = 0; i < len; i++) {
            edge p = multiHopEdgeList[i];
            z += p.weight;
        }

        if (debug_mode > 2)
            System.out.print("Sampled context: ");

        //TODO: probably no need for this j, it should always be equal to i, but just in case I'm wrong
        int j = 0;
        for (int i = 0; i < window; i++) {  // draw 'window' samples

            x = getNextRandomInOpenUnitInterval(next_random);
            cumul_p = 0;

            // Find out in which interval does this belong to...
            for (pIndex = 0; pIndex < len; pIndex++) {
                edge p = multiHopEdgeList[pIndex];
                norm_wt = p.weight / z;
                if (cumul_p <= x && x < cumul_p + norm_wt)
                    break;
                cumul_p += norm_wt;
            }

            // save sampled nodes in context
            contextBuff[j++] = multiHopEdgeList[pIndex];
            if (debug_mode > 2)
                System.out.print(String.format("%s ", vocab[multiHopEdgeList[pIndex].dest.id].word));
        }
        if (debug_mode > 2) System.out.println();;
        return j;
    }

    // Each line in this graph file is of the following format:
    // <src-node-id>\t [<dest-node-id>:<weight of this edge> ]*
    void LearnVocabFromTrainFile() throws IOException {
        // read the input stream once and save the output (this will used a stream any time you need it consequently)
        byte[] trainFile = IOUtils.toByteArray(train_file);
        // first build the vocab
        //TODO: the original was using mode 'rb', why? fin = fopen(train_file, "rb");
        try (BufferedReader fin = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(trainFile)))) {
            if (debug_mode > 2)
                System.out.println("Loading nodes from graph file...");

            System.out.println(vocab_hash_size);
            for (int a = 0; a < vocab_hash_size; a++) vocab_hash[a] = -1;
            vocab_size = 0;

            while (ReadSrcNode(fin)) ;

            SortVocab();
            if (debug_mode > 2) {
                System.out.println(String.format("#nodes: %d", vocab_size));
            }
        }
        // then build the actual graph
        try (BufferedReader fin = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(trainFile)))) {
            constructGraph(fin);
        }

        if (debug_mode > 2)
            System.out.println("Loaded graph in memory...");

        if (!pqsampling) {
            preComputePathContexts();
            if (debug_mode > 2)
                System.out.println("Successfully initialized path contexts");
        }
    }
    
    void loadPretrained() {
        int ptFileNumWords, ptFileVecSize;
        int i;
        UnsignedLong next_random = UnsignedLong.valueOf(SEED);
        
        try {
            ptWordVecs = new WordVecs(pretrained_file, props);
            
            // get num words and dimension of each
            ptFileNumWords = ptWordVecs.getVocabSize();
            ptFileVecSize = ptWordVecs.getDimension();
            
            assert(ptFileVecSize == layer1_size);
            
            pt_syn0 = new float[ptFileNumWords * layer1_size];

            // try to match each node-id (word) of the pt file with the existing vocab
            // (loaded from train file)...
            // if a node in the pt file matches with a node of the current graph
            // then change its (initial) vector to the one read from the pt file
            // if a match is not found, during the later stages, the vector
            // goes as-is to the o/p file
            for (WordVec wvec: ptWordVecs.wordvecmap.values()) {
                int index = SearchVocab(wvec.word);
                if (index >= 0) {
                    // copy the vector as read from the pt file
                    System.arraycopy(wvec.vec, 0, syn0[index * layer1_size], 0, layer1_size);
                }
            }
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
        
        
    }
    
    void InitNet() {
        UnsignedLong next_random = UnsignedLong.valueOf(1);

        syn0 = new float[vocab_size * layer1_size];
        if (negative > 0) {
            syn1neg = new float[vocab_size * layer1_size];
        }

        // Initialize the net weights from a pretrained model
        // The file to be loaded is a binary file saved by word2vec.
        // This is to ensure that the word vectors will not be trained
        if (pretrained_file != null) {
            loadPretrained();
        }
        
        // Random initialization if a current node doesn't exist in the pre-trained file
        // or the pre-trained file doesn't exist itself
        for (int a = 0; a < vocab_size; a++) { // iterate over current nodes
            String word = vocab[a].word;
            float[] wvec = ptWordVecs==null? null : ptWordVecs.getVec(word).vec;
            
            if (wvec == null) { // current word doesn't exist in pt file... hence proceed to random init            
                for (int b = 0; b < layer1_size; b++) {
                    next_random = getNextRandom(next_random);
                    syn0[a * layer1_size + b] = (((next_random.bigIntegerValue().and(BigInteger.valueOf(0xFFFFL)).floatValue()) / (float) 65536) - 0.5f) / layer1_size;//Initialize by random nos
                }
            }
        }
    }
    
    //+++DG: Added the functionality for p-q sampling
    int pqSampling(int src_node_index, UnsignedLong next_random, edge[] contextBuff) {
        
        float x, cumul_p, z, norm_wt;
        vocab_node src_node, next_node = null, prev_node = null;
        edge p = null, q = null;
        int p_index;
        VisitStatus status;
        float delw;
        int j = 0;

        src_node = vocab[src_node_index];
        
        if (src_node.edge_list == null) return 0;
        
        next_node = src_node;
        prev_node = src_node;

        if (debug_mode > 2)        
            System.out.println("Random walk from " + src_node.word + " (" + src_node_index + ")");
        
        if (src_node_index == 16)
            src_node_index = src_node_index;
        
        while (j < window) {
            // normalize the weights so that they sum to 1;
            z = 0;
            for (p_index=0; p_index < next_node.cn; p_index++) {                
                if (next_node.edge_list == null) continue;
                
                p = next_node.edge_list[p_index];
                status = checkNeighbour(prev_node, vocab[p.dest.id]);
                delw = status == VisitStatus.CASE_P? p1 : status == VisitStatus.CASE_ONE? 1 : q1;
                z += p.weight * delw;
            }

            x = getNextRandomInOpenUnitInterval(next_random);

            cumul_p = 0;
            for (p_index=0; p_index < next_node.cn; p_index++) {
                if (next_node.edge_list == null) continue;
                
                p = next_node.edge_list[p_index];
                status = checkNeighbour(prev_node, vocab[p.dest.id]);
                delw = status == VisitStatus.CASE_P? p1 : status == VisitStatus.CASE_ONE? 1 : q1;
                norm_wt = p.weight*delw/z;
                if (cumul_p <= x && x < cumul_p + norm_wt) {
                    q = p;
                    break;
                }
                cumul_p += norm_wt;
                q = p;
            }

            contextBuff[j++] = p_index < next_node.cn? p : q;
            prev_node = next_node;            
            next_node = p_index < next_node.cn? vocab[p.dest.id]: vocab[q.dest.id];
        }
        return j;
    }
    
    VisitStatus checkNeighbour(vocab_node prev_node, vocab_node current_node) {
        edge p;
        int p_index;
        
        if (prev_node.id == current_node.id) {
            return VisitStatus.CASE_Q;
        }

        for (p_index=0; p_index < prev_node.cn; p_index++) {
            if (prev_node.edge_list == null) continue;
            
            p = prev_node.edge_list[p_index];
            if (vocab[p.dest.id].id == current_node.id) {
                return VisitStatus.CASE_ONE;
            }
        }
        return VisitStatus.CASE_P;
    }
    //---DG

    void skipgram() {
        int last_word;
        int l1, l2, target, label;
        int context_len;
        edge[] contextBuff = new edge[MAX_CONTEXT_PATH_LEN];
        UnsignedLong next_random = UnsignedLong.valueOf(123456);
        float f, g;
        float[] neu1e;

        for (int word = 0; word < vocab_size; word++) {
            if (debug_mode > 2) {
                System.out.println(String.format("Skip-gram iteration for source word %s", vocab[word].word));
                System.out.println("Word occurs " + vocab[word].cn + " times");
            }

            // context sampled for each node
            context_len = !pqsampling?
                adjSampling(word, next_random, contextBuff):
                pqSampling(word, next_random, contextBuff);

            // train skip-gram on node contexts
            for (int a = 0; a < context_len; a++) {
                edge p = contextBuff[a];
                // TODO: how is the first case possible?
                if (p == null || p.dest == null) {
                    continue;
                }
                
                // Additional check for the community... Only allow +ve pairs in
                // the training if they have the same community or the intersection
                // of their communities is not null.
                boolean toIncludeInTraining = seedPartitions==null? true: seedPartitions.overlapInCommunity(vocab[word].word, p.dest.word);

                last_word = p.dest.id;
                l1 = last_word * layer1_size;

                //memset(neu1e, 0, layer1_size * sizeof(real));
                // TODO: technically this corresponds more closely to Arrays.fill, not sure this is needed performance-wise
                neu1e = new float[layer1_size];

                // NEGATIVE SAMPLING
                if (negative > 0)
                    for (int d = 0; d < negative + 1; d++) {
                        if (d==0) {
                            target = word;
                            label = toIncludeInTraining? 1 : 0; // +ve example
                        }
                        else { // -ve samples
                            next_random = getNextRandom(next_random);
                            target = table[next_random.bigIntegerValue().shiftRight(16).mod(BigInteger.valueOf(table_size)).intValue()];
                            if (target == 0) target = next_random.bigIntegerValue().mod(BigInteger.valueOf(vocab_size - 1)).intValue() + 1;
                            if (target == word) continue;
                            label = 0;
                        }
                        l2 = target * layer1_size;
                        f = 0;
                        for (int c = 0; c < layer1_size; c++) f += syn0[c + l1] * syn1neg[c + l2];
                        // compute gradient
                        if (f > MAX_EXP) g = (label - 1) * alpha;
                        else if (f < -MAX_EXP) g = (label - 0) * alpha;
                        else g = (label - expTable[(int)((f + MAX_EXP) * (EXP_TABLE_SIZE / MAX_EXP / 2))]) * alpha;

                        for (int c = 0; c < layer1_size; c++) neu1e[c] += g * syn1neg[c + l2];
                        for (int c = 0; c < layer1_size; c++) syn1neg[c + l2] += g * syn0[c + l1];
                    }

                // Learn weights input -> hidden
                for (int c = 0; c < layer1_size; c++) syn0[c + l1] += neu1e[c];

            }
        }
        if (debug_mode > 2)
            System.out.println("Skipgram training done...");
    }
    
    public boolean train() throws IOException {
        
        System.out.println("Starting training using input graph.");
        LearnVocabFromTrainFile();

        if (output_file == null) { System.err.println("Graph file not found"); return false; }
        InitNet();

        if (negative > 0) InitUnigramTable();
        System.out.println("Unigram table initialized...");

        for (int i=0; i < iter; i++)
            skipgram();

        output_file_vec = output_file;

        // Save the word vectors
        try (BufferedWriter fo = new BufferedWriter(new OutputStreamWriter(output_file_vec))) {
            // No need to write out the header... the load of WordVecs can manage this
            for (int a = 0; a < vocab_size; a++) {
                fo.write(String.format("%s ", vocab[a].word));
                for (int b = 0; b < layer1_size; b++)
                    fo.write(String.format("%f ", syn0[a * layer1_size + b]));
                fo.newLine();
            }

            // Write out the rest of the node vectors from the pt file
            // have to keep in mind that the words that were matched with
            // the current graph nodes are already written... so just need to
            // write out the ones that don't match a node of the current (train) graph
            if (ptWordVecs != null) {
                for (WordVec wvec: ptWordVecs.wordvecmap.values()) {
                    int index = SearchVocab(wvec.word);
                    if (index >= 0)
                        continue;

                    fo.write(String.format("%s ", wvec.word));
                    for (int b = 0; b < wvec.vec.length; b++)
                        fo.write(String.format("%f ", wvec.vec[b]));
                    fo.newLine();
                }
            }
        }
        return true;
    }

    int ArgPos(String str, int argc, String[] args) {
        for (int a = 0; a < argc; a++) if (args[a].equals(str)) {
            if (a == argc - 1) {
                System.out.println(String.format("Argument missing for %s", str));
                exit(1);
            }
            return a;
        }
        return -1;
    }

    /** Main program as originally used in command line */
    public boolean run(int argc, String[] argv) throws IOException {
        int i;
       	System.out.print("Node2Vec toolkit v 0.1c\n\n");
        System.out.print("Command Line Options:\n");
        System.out.print("Parameters for training:\n");
        System.out.print("\t-train <file>\n");
        System.out.print("\t\tGraph file (each line a node: <node-id> \t [<node-id>:<weight>]*)\n");
        System.out.print("\t-pt <file>\n");
        System.out.print("\t\tPre-trained vectors for nodes (word2vec bin file format)\n");
        System.out.print("\t-output <file>\n");
        System.out.print("\t\tUse <file> to save the resulting word vectors / word clusters\n");
        System.out.print("\t-size <int>\n");
        System.out.print("\t\tSet size of word vectors; default is 100\n");
        System.out.print("\t-window <int>\n");
        System.out.print("\t\tContext (random walk) length.\n");
        System.out.print("\t-negative <int>\n");
        System.out.print("\t\tNumber of negative examples; default is 5, common values are 3 - 10 (0 = not used)\n");
        System.out.print("\t-iter <int>\n");
        System.out.print("\t\tRun more training iterations (default 5)\n");
        System.out.print("\t-min-count <int>\n");
        System.out.print("\t\tNodes with out-degree less than min-count are discarded; default is 5\n");
        System.out.print("\t-alpha <float>\n");
        System.out.print("\t\tSet the starting learning rate; default is 0.025 for skip-gram\n");
        System.out.print("\t-directed <0/1>\n");
        System.out.print("\t\twhether the graph is directed (if undirected, reverse edges are automatically added when the i/p fmt is edge list>\n");
        System.out.print("\nExample:\n");
        System.out.print("./node2vec -pt ptnodes.vec -train graph.txt -output ovec -size 200 -window 5 -sample 1e-4 -negative 5 -iter 3\n\n");
        
        if ((i = ArgPos("-props", argc, argv)) >= 0) {
            props.load(new FileReader(argv[i + 1]));
            readParameters();
        }
        
        // override the argument values from properties
        if ((i = ArgPos("-partitions", argc, argv)) >= 0) partitionFile = argv[i + 1];
        if ((i = ArgPos("-size", argc, argv)) >= 0) layer1_size = Integer.parseInt(argv[i + 1]);
        if ((i = ArgPos("-onehop_pref", argc, argv)) > 0) onehop_pref = Float.parseFloat(argv[i + 1]);
        if ((i = ArgPos("-trace", argc, argv)) >= 0) debug_mode = Integer.parseInt(argv[i + 1]);
        if ((i = ArgPos("-train", argc, argv)) >= 0) train_file = new FileInputStream(argv[i + 1]);
        if ((i = ArgPos("-alpha", argc, argv)) >= 0) alpha = Float.parseFloat(argv[i + 1]);
        if ((i = ArgPos("-output", argc, argv)) >= 0) output_file = new FileOutputStream(argv[i + 1]);
        if ((i = ArgPos("-directed", argc, argv)) >= 0) directed = Integer.parseInt(argv[i + 1]) != 0;
        if ((i = ArgPos("-pt", argc, argv)) > 0) pretrained_file = new FileInputStream(argv[i + 1]);
        if ((i = ArgPos("-window", argc, argv)) >= 0) window = Integer.parseInt(argv[i + 1]);
        if ((i = ArgPos("-negative", argc, argv)) >= 0) negative = Integer.parseInt(argv[i + 1]);
        if ((i = ArgPos("-iter", argc, argv)) >= 0) iter = Integer.parseInt(argv[i + 1]);
        if ((i = ArgPos("-min-count", argc, argv)) >= 0) min_count = Integer.parseInt(argv[i + 1]);
        if ((i = ArgPos("-dbfs", argc, argv)) >= 0) pqsampling = Boolean.parseBoolean(argv[i + 1]);
        if ((i = ArgPos("-p", argc, argv)) > 0) p1 = Float.parseFloat(argv[i + 1]);
        if ((i = ArgPos("-q", argc, argv)) > 0) q1 = Float.parseFloat(argv[i + 1]);

        System.out.println("Parameters:");
        System.out.println("p:" + p1);
        System.out.println("q:" + q1);
        System.out.println("size:" + layer1_size);
        System.out.println("window:" + window);
        System.out.println("ns:" + negative);
        System.out.println("iter:" + iter);
        System.out.println("alpha:" + alpha);
        
        loadFiles();
        
        if (window > MAX_CONTEXT_PATH_LEN) {
            System.out.println(String.format("Window size %d value too large. Truncating the value to %d\n", window, MAX_CONTEXT_PATH_LEN));
            window = MAX_CONTEXT_PATH_LEN;
        }
        one_minus_onehop_pref = 1 - onehop_pref;
        vocab = new vocab_node[vocab_max_size];
        vocab_hash = new int[vocab_hash_size];
        expTable = new float[EXP_TABLE_SIZE + 1];
        for (i = 0; i < EXP_TABLE_SIZE; i++) {
            // TODO: C and Java have the same 'exp' function, but need to check floats again
            expTable[i] = (float)Math.exp((i / (float)EXP_TABLE_SIZE * 2 - 1) * MAX_EXP); // Precompute the exp() table
            expTable[i] = expTable[i] / (expTable[i] + 1);                   // Precompute f(x) = x / (x + 1)
        }
        return train();
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: Node2Vec <properties file>");
            System.err.println("Using default properties file...");
            
            args = new String[1];
            args[0] = "-props init.properties";
        }
        
        Node2Vec cmd = new Node2Vec();
        cmd.run(args.length, args);
    }    
}
