package edu.stanford.nlp.mt.tools.deplm;

import java.io.IOException;
import java.io.LineNumberReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import edu.stanford.nlp.mt.lm.LanguageModel;
import edu.stanford.nlp.mt.lm.LanguageModelFactory;
import edu.stanford.nlp.mt.util.IOTools;
import edu.stanford.nlp.mt.util.IString;
import edu.stanford.nlp.mt.util.Sequence;
import edu.stanford.nlp.mt.util.Sequences;
import edu.stanford.nlp.mt.util.SimpleSequence;
import edu.stanford.nlp.util.Generics;
import edu.stanford.nlp.util.Pair;

/**
 * Evaluate the perplexity of an input file under a language model.
 * 
 * @author danielcer
 * @author Spence Green
 * @author Sebastian Schuster
 *
 */
public final class DependencyLanguageModelPerplexity2 {
  
  private static LanguageModel<IString> childLm;
  private static LanguageModel<IString> headLm;
  
  private static String HEAD_SUFFIX = "<HEAD>";
  private static String SIBLING_SUFFIX = "<SIB>";



  private static final IString ROOT_TOKEN = new IString("<ROOT>");
  private static final IString FRAG_TOKEN = new IString("<FRAG>");
  private static final IString START_TOKEN = new IString("<START>");
  private static final IString END_TOKEN = new IString("<END>");

  private static final IString ROOT_DIR_TOKEN = new IString("0<DIR>");
  private static final IString LEFT_DIR_TOKEN = new IString("1<DIR>");
  private static final IString RIGHT_DIR_TOKEN = new IString("2<DIR>");


  static int wordCount = 0;
  
  
  private static double scoreHead(IString child, IString head) throws IOException {

    return 0.0;
//    head = new IString(head + HEAD_SUFFIX);
//    
//    List<IString> tokens = new LinkedList<IString>();
//    tokens.add(head);
//    tokens.add(child);
//
//    Sequence<IString> seq = new SimpleSequence<IString>(tokens);
//    
//    double score = headLm.score(seq, 1, null).getScore();
//    
//    wordCount += seq.size() - 1;
//    
//    return score;
    
   }
  
  
  private static double scoreChild(IString child, IString sibling, IString head, IString direction) throws IOException {
    
    head = new IString(head + HEAD_SUFFIX);
    sibling = new IString(sibling + SIBLING_SUFFIX);

    List<IString> tokens = new LinkedList<IString>();
    tokens.add(sibling);
    tokens.add(direction);
    tokens.add(head);
    tokens.add(child);

    Sequence<IString> seq = new SimpleSequence<IString>(tokens);

    double score = childLm.score(seq, 3, null).getScore();
    
    System.err.println("Scoring: " + seq);
    
    wordCount += seq.size() - 3;
    
    return score;

    
  }
  
  private static double scoreTree(HashMap<Integer, Pair<String, List<Integer>>> dependencies) throws IOException {

    double score = 0.0;

    for (int gov : dependencies.keySet()) {
      
      String headWord = dependencies.get(gov).first;
      if (headWord != null && !DependencyUtils.isWord(headWord))
        continue;
      
      if (gov < 1) {
        for (Integer dep : dependencies.get(gov).second) {
          String word = dependencies.get(dep).first.toLowerCase();
          if (!DependencyUtils.isWord(word))
            continue;
          
          IString depToken = new IString(word); 
          IString headToken = gov == 0 ? ROOT_TOKEN : FRAG_TOKEN;
          
          score += scoreHead(depToken, headToken);
          score += scoreChild(depToken, START_TOKEN, headToken, ROOT_DIR_TOKEN);
          score += scoreChild(END_TOKEN, depToken, headToken, ROOT_DIR_TOKEN);
        }
          
      } else {
        List<IString> leftChildren = Generics.newLinkedList();
        List<IString> rightChildren = Generics.newLinkedList();
        
        List<Integer> sortedChildren = Generics.newLinkedList();
        sortedChildren.addAll(dependencies.get(gov).second);
        Collections.sort(sortedChildren);
        for (Integer dep : sortedChildren) {
          String word = dependencies.get(dep).first.toLowerCase();
          if (!DependencyUtils.isWord(word))
            continue;
          if (dep < gov) {
            leftChildren.add(new IString(word));
          } else {
            rightChildren.add(new IString(word));
          }
        }
        
        Collections.reverse(leftChildren);
        IString headToken = new IString(headWord);
        
        int leftChildrenCount = leftChildren.size();
        
        for (int i = 0; i <= leftChildrenCount; i++) {
          IString depToken1 =  (i > 0) ?  leftChildren.get(i-1) : START_TOKEN;
          IString depToken2 =  (i < leftChildrenCount) ?  leftChildren.get(i) : END_TOKEN;
          if (i > 0)
            score += scoreHead(depToken1, headToken);
          
          score += scoreChild(depToken2, depToken1, headToken, LEFT_DIR_TOKEN);
        }
        
        
        int rightChildrenCount = rightChildren.size();
        for (int i = 0; i <= rightChildrenCount; i++) {
          IString depToken1 =  (i > 0) ?  rightChildren.get(i-1) : START_TOKEN;
          IString depToken2 =  (i < rightChildrenCount) ?  rightChildren.get(i) : END_TOKEN;
          if (i > 0)
            score += scoreHead(depToken1, headToken);
          score += scoreChild(depToken2, depToken1, headToken, RIGHT_DIR_TOKEN);
        }
      }
    }
    return score;

  }  
  
  
 
  
  /**
   * 
   * @param args
   */
  public static void main(String[] args) throws IOException {
    if (args.length != 3) {
      System.err
          .printf("Usage: java %s type:path_childlm type:path_headlm input_file.conll%n", DependencyLanguageModelPerplexity2.class.getName());
      System.exit(-1);
    }

    String childModel = args[0];
    String headModel = args[1];
    
    System.out.printf("Loading child lm: %s...%n", childModel);
    childLm = LanguageModelFactory.load(childModel);

    System.out.printf("Loading head lm: %s...%n", headModel);
    headLm = LanguageModelFactory.load(headModel);
        
    LineNumberReader reader = IOTools.getReaderFromFile(args[2]);
    
    HashMap<Integer, Pair<String, List<Integer>>> dependencies;
    
    double logSum = 0.0;
    final long startTimeMillis = System.nanoTime();
    
    while ((dependencies = DependencyUtils.getDependenciesFromCoNLLFileReader(reader, false)) != null) {
      final double score = scoreTree(dependencies);
      assert score != 0.0;
      assert ! Double.isNaN(score);
      assert ! Double.isInfinite(score);
      logSum += score;
    }
        
    reader.close();
    System.out.printf("Word count: %d%n", wordCount);
    System.out.printf("Log sum score: %e%n", logSum);
    System.out.printf("Log10 sum score: %e%n", logSum / Math.log(10.0));
    System.out.printf("Log2 sum score: %e%n", logSum / Math.log(2.0));
    System.out.printf("Log2 Perplexity: %e%n", Math.pow(2.0, -logSum / Math.log(2.0) / wordCount));
    System.out.printf("Log10 Perplexity: %e%n", Math.pow(10.0, -logSum / Math.log(10.0) / wordCount));

       
    double elapsed = (System.nanoTime() - startTimeMillis) / 1e9;
    System.err.printf("Elapsed time: %.3fs%n", elapsed);
  }
}
