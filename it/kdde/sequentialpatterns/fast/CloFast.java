package it.kdde.sequentialpatterns.fast;

import it.kdde.sequentialpatterns.model.*;
import it.kdde.sequentialpatterns.model.tree.*;
import util.Statistics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class CloFast {

    private FastDataset ds;

    private ClosedSequenceTree outputTree;

    public final Statistics statistics;

    public CloFast(FastDataset ds) {
        this.ds = ds;
        statistics = new Statistics();
    }

    public void run() throws IOException {

        statistics.startMemory();
        statistics.startTimeItemset();
        List<ClosedItemsetNode> closedNodes = generateClosedItemsets();
        statistics.endTimeItemset();

        statistics.startTimeSequence();
        outputTree = generateClosedSequences(closedNodes);
        statistics.endTimeSequence();
        statistics.endMemory();
    }

    private List<ClosedItemsetNode> generateClosedItemsets() {
        final ClosedItemsetTree tree = new ClosedItemsetTree();
        final Map<Integer, List<ClosedItemsetNode>> closedTable = new HashMap<>();

        final Queue<ClosedItemsetNode> queue = new LinkedList<>();
        int pos = 0;
        ClosedItemsetNode node;

        for (Map.Entry<String, SparseIdList> entry : ds.getFrequentItemsets().entrySet()) {
            node = tree.addChild(tree.getRoot(), new Itemset(entry.getKey()), entry.getValue(), pos++);
            queue.add(node);
        }

        while (!queue.isEmpty()) {
            node = queue.remove();
            closedItemsetExtension(tree, node, closedTable);
            queue.addAll(node.getChildren());
        }

        final List<ClosedItemsetNode> result = new ArrayList<>();
        closedTable.values().stream().forEach(l -> result.addAll(l));

        Collections.sort(result);
        return result;

    }

    private void closedItemsetExtension(ClosedItemsetTree tree, ClosedItemsetNode node, Map<Integer, List<ClosedItemsetNode>> closedTable) {

        boolean sentinel = false;
        int pos = 0;

        List<ClosedItemsetNode> children = node.getParent().getChildren();

        for (int i = node.getPosition() + 1; i < children.size(); i++) {
            ClosedItemsetNode rightBrother = children.get(i);
            SparseIdList sil = SparseIdList.IStep(node.getIdList(), rightBrother.getIdList());

            if (sil.getAbsoluteSupport() >= ds.getAbsMinSup()) {

                //un nodo è considerato intermediate solo se ha stesso supporto e  stesso sil
                if (sil.getAbsoluteSupport() == node.getIdList().getAbsoluteSupport() & sil.equals(node.getIdList())) {
                    // this is an intermediate node
                    // some of its children could be a closed itemsets
                    node.setType(ItemsetNodeType.intermediate);
                    sentinel = true;
                }
                //add new node
                Itemset itemset = node.getItemset().clone();
                itemset.addItem(rightBrother.getItemset().getLast());
                tree.addChild(node, itemset, sil, pos++);
            }
        }

        if (!sentinel) {
            if (!leftcheck(node, closedTable)) {
                node.setType(ItemsetNodeType.closed);
                closedTable.putIfAbsent(node.getAbsoluteSupport(), new ArrayList<>());
                closedTable.get(node.getAbsoluteSupport()).add(node);
            }
        }
    }

    /**
     * check if there is a node that closes the given node otherwise removes the
     *
     * @param nodeToCheck
     * @param closedTable
     * @return
     */
    private boolean leftcheck(ClosedItemsetNode nodeToCheck, Map<Integer, List<ClosedItemsetNode>> closedTable) {

        Integer nodeSupp = nodeToCheck.getIdList().getAbsoluteSupport();
        final List<ClosedItemsetNode> toRemove = new ArrayList<>();

        List<ClosedItemsetNode> list = closedTable.getOrDefault(nodeSupp, new ArrayList<>());
        //TODO remove this if
        if (closedTable.containsKey(nodeSupp)) {
            for (ClosedItemsetNode candidateClosed : list) {

                if (candidateClosed.getItemset().contains(nodeToCheck.getItemset()))
                    return true;

                if (nodeToCheck.getItemset().contains(candidateClosed.getItemset()) &&
                        nodeToCheck.getIdList().equals(candidateClosed.getIdList())) { //questa linea e' stata aggiunta per verificare
                    //la proprieta di chiusura delle SIL
                    toRemove.add(candidateClosed);
                    candidateClosed.setType(ItemsetNodeType.notClosed);
                }
            }
        }

        list.removeAll(toRemove);
        return false;
    }


    private ClosedSequenceTree generateClosedSequences(List<ClosedItemsetNode> closedNodes) {

        ClosedSequenceTree tree = new ClosedSequenceTree(ds.getAbsMinSup());

        for (ClosedItemsetNode node : closedNodes) {
            tree.addChild(tree.getRoot(), new Sequence(node.getItemset()), node.getIdList().getStartingVIL(),
                    node.getAbsoluteSupport());
        }

        for (ClosedSequenceNode csn : tree.getRoot().getChildren())
            closedSequenceExtension(tree, csn);

        return tree;
    }

    /**
     * @param csn
     */
    private void closedSequenceExtension(ClosedSequenceTree tree, ClosedSequenceNode csn) {


        // check for backward closure
        if (csn.getType() == NodeType.toCheck) {
            if (closedByBackwardExtension(tree, csn)) {
                if (csn.getType() != NodeType.pruned)
                    csn.setType(NodeType.notClosed);
            } else {
                csn.setType(NodeType.closed);
            }
        }

        if (csn.getType() == NodeType.pruned)
            return;

        ListNode listNode, listNodeBrother;
        ListNode[] newPosList, bListNode;
        ListNode[] csnListNode = csn.getVerticalIdList().getElements();
        int count = 0;

        // generate children and check if some of its children close it
        List<ClosedSequenceNode> brothers = csn.getParent().getChildren();

        for (ClosedSequenceNode b : brothers) {
            newPosList = new ListNode[csnListNode.length];
            bListNode = b.getVerticalIdList().getElements();

            for (int i = 0; i < csnListNode.length; i++) {

                listNode = csnListNode[i];
                listNodeBrother = bListNode[i];

                // when i found a null element I exit the for
                if ((listNode == null) || (listNodeBrother == null)) {
                    continue;
                }

                if ((listNodeBrother.getColumn() > listNode.getColumn())) {
                    newPosList[i] = listNodeBrother;
                    count++;

                } else if ((listNodeBrother.getColumn() <= listNode.getColumn())) {
                    while ((listNodeBrother != null)
                            && (listNodeBrother.getColumn() <= listNode
                            .getColumn())) {
                        listNodeBrother = listNodeBrother.next();
                    }
                    if (listNodeBrother != null) {
                        newPosList[i] = listNodeBrother;
                        count++;
                    }
                }
            }

            if (count >= ds.getAbsMinSup()) {

                // clone sequence
                Sequence sequence = csn.getSequence().clone();
                sequence.add(b.getSequence().getLastItemset());
                tree.addChild(csn, sequence, new VerticalIdList(newPosList, count), count);

                // check if the new node close csn in forward extension
                if (count == csn.getAbsoluteSupport())
                    csn.setType(NodeType.notClosed);
            }
            count = 0;
        }

        List<ClosedSequenceNode> children = csn.getChildren();

        // depth visit call
        for (ClosedSequenceNode n : children) {
            // System.out.println(n.getSequence()+"-"+n.getSupport());
            closedSequenceExtension(tree, n);
        }

    }

    /**
     * this method checks if the node csn can be closed by a backward extension.
     * check if closed in the sequence. It evaluates level by level if level
     * brother of the considered node in the sequence can extend the sequence
     * and close the sequence. given the sequence a -> b where the frequent
     * brother of b are a and c, and the frequent brother of a are a, b and c it
     * at first consider b and evaluate if a -> b -> b closes a -> b, then if a
     * -> c -> b closes a -> b then it evaluates if a -> a -> b, or b -> a -> b,
     * or c -> a -> b closes a -> b. if one of this sequence closes a -> b then
     * a -> b is not closed
     */
    private boolean closedByBackwardExtension(ClosedSequenceTree tree, ClosedSequenceNode csn) {

        List<Integer> validRows = new ArrayList<>();
        // these are all the columns with value not equal to null
        for (int i = 0; i < csn.getVerticalIdList().getElements().length; i++) {
            if (csn.getVerticalIdList().getElements()[i] != null)
                validRows.add(i);
        }

        ClosedSequenceNode predNode, currentNode;
        LinkedList<ClosedSequenceNode> succsNodes = new LinkedList<>();
        succsNodes.addFirst(csn);

        currentNode = csn;

        // check if closed in the sequence. It evaluates level by level if level
        // brother of the considered
        // node in the sequence can extend the sequence and close the sequence
        while (currentNode.getParent() != tree.getRoot()) {
            predNode = currentNode.getParent();

            // the children of the pred node to check for insertion
            List<ClosedSequenceNode> betweenNodes = predNode.getChildren();

            for (ClosedSequenceNode betweenNode : betweenNodes) {

                //it is useless to analyze pruned nodes
                if(betweenNode.getType()==NodeType.pruned)
                    continue;

                // remove current node because it has not be considered for self
                // extension
                if (betweenNode == csn)
                    continue;

                // check if to do itemsetClosure or sequenceClosure
                if (betweenNode.containsLastItemset(succsNodes.getFirst())) {
                    // check for itemset closure
                    if (itemsetClosure(betweenNode, succsNodes, validRows, csn))
                        return true;
                }
                // if arrives here we need to check for sequenceClosure
                if (sequenceClosure(predNode, betweenNode, succsNodes, validRows, csn))
                    return true;

            }
            // add the predNode for the current Iteration
            succsNodes.addFirst(predNode);
            currentNode = predNode;
        }

        // check if closed on the head
        List<ClosedSequenceNode> predNodes = currentNode.getParent().getChildren();
        for (ClosedSequenceNode pred : predNodes) {
            if (pred.containsLastItemset(succsNodes.getFirst())) {
                if (itemsetClosure(pred, succsNodes, validRows, csn))
                    return true;
            }
            if (sequenceClosure(pred, succsNodes, validRows, csn))
                return true;
        }

        return false;
    }

    /**
     * @param predNode
     * @param succsNodes
     * @param validRows
     * @param csn
     * @return
     */
    private boolean sequenceClosure(ClosedSequenceNode predNode, LinkedList<ClosedSequenceNode> succsNodes,
                                    List<Integer> validRows, ClosedSequenceNode csn) {

        ListNode[] predVil = predNode.getVerticalIdList().getElements();

        // this is used to check for pruning
        ListNode[] candidateClosureVil = new ListNode[predVil.length];

        for (Integer i : validRows) {

            if (predVil[i] == null)
                return false;

            // if (predVil[i].before(succsNodes, i) == null) {
            // return false;
            // }

            ListNode closureNode = predVil[i].before(succsNodes, i);

            if (closureNode == null) {
                return false;
            } else
                candidateClosureVil[i] = closureNode;

        }

        if (sameVil(candidateClosureVil, csn.getVerticalIdList().getElements(), validRows))
            csn.setType(NodeType.pruned);

        return true;
    }

    /**
     * @param predNode
     * @param backwardNode
     * @param succsNodes
     * @param validRows
     * @return
     */
    private boolean sequenceClosure(ClosedSequenceNode predNode, ClosedSequenceNode backwardNode,
                                    LinkedList<ClosedSequenceNode> succsNodes,
                                    List<Integer> validRows, ClosedSequenceNode csn) {

        ListNode[] predVil = predNode.getVerticalIdList().getElements();
        ListNode[] backwardVil = backwardNode.getVerticalIdList().getElements();

        // this is used to check for pruning
        ListNode[] candidateClosureVil = new ListNode[predVil.length];

        for (Integer i : validRows) {

            if (backwardVil[i] == null)
                return false;

            if (predVil[i].getColumn() > backwardVil[i].getColumn())
                return false;

            ListNode closureNode = backwardVil[i].before(succsNodes, i);

            if (closureNode == null) {
                return false;
            } else {
                candidateClosureVil[i] = closureNode;
            }
        }
        //early termination
        if (sameVil(candidateClosureVil, csn.getVerticalIdList().getElements(), validRows))
            csn.setType(NodeType.pruned);

        return true;
    }

    private boolean sameVil(ListNode[] candidateClosureVil,
                            ListNode[] positionsList, List<Integer> validColumns) {

        for (Integer i : validColumns) {
            if (candidateClosureVil[i].getColumn() != positionsList[i]
                    .getColumn())
                return false;
        }

        return true;
    }

    /**
     * @param backwardNode it is the node to be inserted in backward
     * @param succsNodes   the nodes after. Using and example if a -> b -> c -> d is the
     *                     sequence and c is the current node then 1. backward node, for
     *                     example e, is the node to be inserted 2. succnNodes containsItemset c
     *                     and d, where c is the current node and d is the successive
     *                     node
     * @param validRows    is the list of all the columns with id not equal to null
     * @param csn          is the current node
     * @return
     */
    private boolean itemsetClosure(ClosedSequenceNode backwardNode, LinkedList<ClosedSequenceNode> succsNodes,
                                   List<Integer> validRows, ClosedSequenceNode csn) {

        ListNode[] backwardVil = backwardNode.getVerticalIdList().getElements();

        // this is used to check for pruning
        ListNode[] candidateClosureVil = new ListNode[backwardVil.length];

        for (Integer i : validRows) {

            if (backwardVil[i] == null)
                return false;

            // if (betweenVil[i].equal(succsNodes, i) == null)
            // return false;

            ListNode closureNode = equal(backwardVil[i], succsNodes, i);

            if (closureNode == null) {
                return false;
            } else {
                candidateClosureVil[i] = closureNode;
            }

        }
        // early termination
        if (sameVil(candidateClosureVil, csn.getVerticalIdList().getElements(), validRows))
            csn.setType(NodeType.pruned);

        return true;
    }

    /**
     * @param succNodes
     * @param i
     * @return verify the for the row i we can shift all the positions of the successive nodes
     * without affecting the support of the sequences children of the given node
     */
    private ListNode equal(ListNode node, LinkedList<ClosedSequenceNode> succNodes, Integer i) {
        ListNode curr = node;

        Iterator<ClosedSequenceNode> it = succNodes.iterator();

        ListNode succ = it.next().getVerticalIdList().getElements()[i];
        succ = curr.equal(succ);
        if (succ == null)
            return null;
        else {
            while (it.hasNext()) {
                ClosedSequenceNode n = it.next();
                succ = succ.before(n.getVerticalIdList().getElements()[i]);
                if (succ == null)
                    return null;
            }
        }
        return succ;
    }


    public List<ClosedSequenceNode> getClosedFrequentNodes() {
        return ClosedSequenceTree.visit(outputTree);
    }


    private void writePatterns(Path outputFile) throws IOException {
        final BufferedWriter out = Files.newBufferedWriter(outputFile);

        List<ClosedSequenceNode> nodes = getClosedFrequentNodes();

        int countClosed = 0;
        int countPruned = 0;

        for (ClosedSequenceNode node : nodes) {

            switch (node.getType()) {
                case closed:
                    out.write(node.toString() + "\n");
                    countClosed++;
                    break;
                case pruned:
                    countPruned++;
                    break;
            }
        }
        out.flush();
        out.close();

        statistics.setNumClosedFrequentSequences(countClosed);
        statistics.setNumSequencesPruned(countPruned);
        statistics.setNumFrequentSequenceGenerated(nodes.size());
    }

    private void writeStatistic(String datasetName, float minSupp, int absMinSup, String statisticsFile) throws IOException {
        statistics.printClosedSequencesStat("CloFAST",datasetName, minSupp, absMinSup, statisticsFile);
    }


    public static void main(String[] args) throws IOException {

        if (args.length != 3) {
            System.err.println("the need parameters are sequence_file, min_supp, statistics_file");
            System.err.println("sequences.txt 0.2 statistics.txt");
        } else {
            String inputFile = args[0];
            float minSupp = Float.parseFloat(args[1]);
            String statisticsFile = args[2];
            int lastPointIndex = inputFile.lastIndexOf(".");
            String outputFile = inputFile + "_" + minSupp + ".txt";
            System.out.println("Start loading the dataset");
            FastDataset ds = FastDataset.fromPrefixspanSource(Paths.get(inputFile), minSupp);
            System.out.println("End loading the dataset");

            CloFast cloFast = new CloFast(ds);
            System.out.println("Start closed sequence extraction");
            cloFast.run();
            System.out.println("End closed sequence extraction");

            //save patterns
            cloFast.writePatterns(Paths.get(outputFile));

            cloFast.writeStatistic(inputFile, minSupp, ds.getAbsMinSup(), statisticsFile);

        }
    }
}
