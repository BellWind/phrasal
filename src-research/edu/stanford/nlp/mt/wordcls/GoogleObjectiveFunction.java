package edu.stanford.nlp.mt.wordcls;

import java.util.Map;
import java.util.Set;

import edu.stanford.nlp.mt.base.IString;
import edu.stanford.nlp.stats.ClassicCounter;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.TwoDimensionalCounter;
import edu.stanford.nlp.util.Generics;

/**
 * The one-sided class model of Uszkoreit and Brants (2008).
 * 
 * @author Spence Green
 *
 */
public class GoogleObjectiveFunction {

  private double objValue = 0.0;

  private final ClustererInput input;
  private final Map<IString, Integer> localWordToClass;
  private final Counter<Integer> localClassCount;
  private final TwoDimensionalCounter<Integer, NgramHistory> localClassHistoryCount;

  public GoogleObjectiveFunction(ClustererInput input) {
    // Setup delta data structures
    this.input = input;
    localWordToClass = Generics.newHashMap(input.vocab.size());
    localClassCount = new ClassicCounter<Integer>(input.classCount.keySet().size());
    localClassHistoryCount = new TwoDimensionalCounter<Integer,NgramHistory>();
    for (IString word : input.vocab) {
      int classId = input.wordToClass.get(word);
      localWordToClass.put(word, classId);
      localClassCount.incrementCount(classId);
      Counter<NgramHistory> historyCount = 
          new ClassicCounter<NgramHistory>(input.classHistoryCount.getCounter(classId));
      localClassHistoryCount.setCounter(classId, historyCount);
    }

    // Compute initial objective function value from the input data structures
    // Later values will be updated with the delta data structures
    // First summation
    for (Integer classId : input.classHistoryCount.firstKeySet()) {
      Counter<NgramHistory> historyCount = input.classHistoryCount.getCounter(classId);
      for (NgramHistory history : historyCount.keySet()) {
        double count = historyCount.getCount(history);
        objValue += count * Math.log(count);
      }
    }
    // Second summation
    for (Integer classId : input.classCount.keySet()) {
      double count = input.classCount.getCount(classId);
      objValue -= count * Math.log(count);
    }
  }

  public ClustererOutput cluster() {
    Set<Integer> wordClasses = input.classCount.keySet();
    for (IString word : input.vocab) {
      final Integer currentClass = localWordToClass.get(word);
      Integer argMax = currentClass;
      double maxObjectiveValue = objValue;
      final Counter<NgramHistory> historiesForWord = input.historyCount.getCounter(word);

      // Remove the word from the local data structures
      double reducedObjValue = objectiveAfterRemoving(word, currentClass);
//      assert reducedObjValue < objValue;

      // Compute objective value under tentative moves
      for (Integer classId : wordClasses) {
        if (classId == currentClass) continue;
        double objDelta = 0.0;
        final Counter<NgramHistory> classHistory = localClassHistoryCount.getCounter(classId);
        for (NgramHistory history : historiesForWord.keySet()) {
          double oldCount = classHistory.getCount(history);
          double count = historiesForWord.getCount(history);
          if (oldCount > 0.0) {
            // Remove the old term
            objDelta -= oldCount * Math.log(oldCount);
          }
          double newCount = oldCount + count;
          // Add the new term
          objDelta += newCount * Math.log(newCount);
        }
        double classCount = localClassCount.getCount(classId);
        if (classCount > 0.0) {
          // Remove the old term
          objDelta += classCount * Math.log(classCount);
        }
        ++classCount;
        // Add the new term
        objDelta -= classCount * Math.log(classCount);

        if (reducedObjValue + objDelta > maxObjectiveValue) {
          argMax = classId;
          maxObjectiveValue = reducedObjValue + objDelta;
        }
      }
      // Final move
      if (argMax != currentClass) {
        move(word, currentClass, argMax);
      }
//      assert objValue == maxObjectiveValue : String.format("%.10f vs. %.10f", objValue, maxObjectiveValue);
    }
    return new ClustererOutput(localWordToClass, localClassCount, localClassHistoryCount);
  }

  /**
   * Explicitly update the local data structures and objective function value.
   * 
   * @param word
   * @param fromClass
   * @param toClass
   */
  private void move(IString word, Integer fromClass, Integer toClass) {
    final Counter <NgramHistory> historiesForFromClass = this.localClassHistoryCount.getCounter(fromClass);
    final Counter <NgramHistory> historiesForToClass = this.localClassHistoryCount.getCounter(toClass);
    final Counter<NgramHistory> historiesForWord = input.historyCount.getCounter(word);
    // Update first term
    for (NgramHistory history : historiesForWord.keySet()) {
      double fromCount = historiesForFromClass.getCount(history);
      assert fromCount > 0.0;
      double toCount = historiesForToClass.getCount(history);
      double count = historiesForWord.getCount(history);
      objValue -= fromCount*Math.log(fromCount);
      if (toCount > 0.0) {
        objValue -= toCount*Math.log(toCount);
      }
      fromCount -= count;
      toCount += count;
      historiesForFromClass.setCount(history, fromCount);
      historiesForToClass.setCount(history, toCount);
      if (fromCount > 0.0) {
        objValue += fromCount*Math.log(fromCount);
      }
      objValue += toCount*Math.log(toCount);
    }

    // Update second term
    double fromClassCount = localClassCount.getCount(fromClass);
    assert fromClassCount > 0.0;
    double toClassCount = localClassCount.getCount(toClass);
    objValue += fromClassCount*Math.log(fromClassCount);
    if (toClassCount > 0.0) {
      objValue += toClassCount*Math.log(toClassCount);
    }
    localClassCount.decrementCount(fromClass);
    localClassCount.incrementCount(toClass);
    --fromClassCount;
    ++toClassCount;
    if (fromClassCount > 0.0) {
      objValue -= fromClassCount*Math.log(fromClassCount);
    }
    objValue -= toClassCount*Math.log(toClassCount);
  }

  /**
   * Implicitly decrement local data structures and return objective data function
   * value.
   * 
   * @param word
   * @param currentClass 
   * @return
   */
  private double objectiveAfterRemoving(IString word, Integer currentClass) {
    double reducedObjective = objValue;
    final Counter<NgramHistory> historiesForWord = input.historyCount.getCounter(word);
    final Counter<NgramHistory> classHistory = localClassHistoryCount.getCounter(currentClass);
    for (NgramHistory history : historiesForWord.keySet()) {
      double currentCount = classHistory.getCount(history);
      assert currentCount > 0.0;
      double count = historiesForWord.getCount(history);
      assert currentCount - count >= 0.0;
      // Remove original term
      reducedObjective -= currentCount * Math.log(currentCount);
      double newCount = currentCount - count;
      if (newCount > 0.0) {
        // Add updated term
        reducedObjective += newCount * Math.log(newCount);
      }
    }
    double classCount = localClassCount.getCount(currentClass);
    // Remove original term
    reducedObjective += classCount * Math.log(classCount);
    --classCount;
    if (classCount > 0.0) {
      // Add updated term
      reducedObjective -= classCount * Math.log(classCount);
    }
    return reducedObjective;
  }
}