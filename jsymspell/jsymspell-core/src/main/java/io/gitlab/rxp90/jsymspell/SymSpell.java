package io.gitlab.rxp90.jsymspell;

import io.gitlab.rxp90.jsymspell.api.DamerauLevenshteinOSA;
import io.gitlab.rxp90.jsymspell.api.EditDistance;
import io.gitlab.rxp90.jsymspell.api.StringHasher;
import io.gitlab.rxp90.jsymspell.exceptions.NotInitializedException;

import java.util.*;

import static io.gitlab.rxp90.jsymspell.SymSpell.Verbosity.ALL;
import static java.lang.Long.parseLong;

public class SymSpell {
  /**
   * Delimiter for lexicon bigrams and unigrams (words).
   */
  public final static String LEXICON_DELIMITER = "\t";

  private final int maxDictionaryEditDistance;
  private final int prefixLength;
  private final int countThreshold;

  private final Map<Long, String[]> deletes = new HashMap<>();
  private final Map<String, Long> lexiconWords = new HashMap<>();
  private final Map<String, Long> lexiconBigrams = new HashMap<>();
  private final Map<String, Long> belowThresholdWords = new HashMap<>();
  private final EditDistance damerauLevenshteinOSA;

  private final StringHasher stringHasher;

  private int maxDictionaryWordLength;

  /**
   * Number of all words in the corpus that was used to generate the
   * frequency lexicon this is used to calculate the word occurrence
   * probability {@code p} from word counts {@code c}: {@code p=c/N}.
   * <p>
   * {@code N} equals the sum of all counts {@code c} in the lexicon only
   * if the lexicon is complete, but not if the lexicon is truncated or
   * filtered.
   * </p>
   */
  private final static long N = 1024908267229L;
  private long bigramCountMin = Long.MAX_VALUE;

  public enum Verbosity {
    TOP,
    CLOSEST,
    ALL
  }

  SymSpell(
      final int maxDictionaryEditDistance,
      final int prefixLength,
      final int countThreshold,
      final StringHasher stringHasher,
      final Map<Long, String[]> deletes,
      final Collection<String> lexiconWords,
      final Collection<String> lexiconBigrams ) {
    this.maxDictionaryEditDistance = maxDictionaryEditDistance;
    this.prefixLength = prefixLength;
    this.countThreshold = countThreshold;
    this.stringHasher = stringHasher;
    this.deletes.putAll( deletes );
    this.damerauLevenshteinOSA = new DamerauLevenshteinOSA();

    initLexiconWords( lexiconWords );
    initLexiconBigrams( lexiconBigrams );
  }

  private boolean deleteSuggestionPrefix(
      String delete, int deleteLen, String suggestion, int suggestionLen ) {
    if( deleteLen == 0 ) {
      return true;
    }

    int adjustedSuggestionLen = Math.min( prefixLength, suggestionLen );

    int j = 0;

    for( int i = 0; i < deleteLen; i++ ) {
      char delChar = delete.charAt( i );
      while( j < adjustedSuggestionLen && delChar != suggestion.charAt( j ) ) {
        j++;
      }
      if( j == adjustedSuggestionLen ) {
        return false;
      }
    }
    return true;
  }

  Set<String> edits( String word, int editDistance, Set<String> deleteWords ) {
    editDistance++;
    if( word.length() > 1 && editDistance <= maxDictionaryEditDistance ) {
      for( int i = 0; i < word.length(); i++ ) {
        StringBuilder editableWord = new StringBuilder( word );
        String delete = editableWord.deleteCharAt( i ).toString();
        if( deleteWords.add( delete ) ) {
          if( editDistance < maxDictionaryEditDistance ) {
            edits( delete, editDistance, deleteWords );
          }
        }
      }
    }
    return deleteWords;
  }

  private Set<String> editsPrefix( String key ) {
    Set<String> set = new HashSet<>();
    if( key.length() <= maxDictionaryEditDistance ) {
      set.add( "" );
    }
    if( key.length() > prefixLength ) {
      key = key.substring( 0, prefixLength );
    }
    set.add( key );
    return edits( key, 0, set );
  }

  private Map.Entry<String, Long> parse( final String line ) {
    final String[] parts = line.split( LEXICON_DELIMITER );
    return new AbstractMap.SimpleEntry<>( parts[ 0 ], parseLong( parts[ 1 ] ) );
  }

  private void initLexiconWords( final Collection<String> lexicon ) {
    final SuggestionStage staging = new SuggestionStage( 16384 );

    lexicon.forEach( line -> {
      final Map.Entry<String, Long> entry = parse( line );
      createDictionaryEntry( entry, staging );
    } );

    commitStaged( staging );
  }

  private void initLexiconBigrams( final Collection<String> lexicon ) {
    lexicon.forEach( line -> {
      final Map.Entry<String, Long> entry = parse( line );
      final long count = entry.getValue();
      lexiconBigrams.put( entry.getKey(), count );
      if( count < bigramCountMin ) {
        bigramCountMin = count;
      }
    } );
  }

  private void commitStaged( final SuggestionStage staging ) {
    staging.commitTo( deletes );
  }

  private void createDictionaryEntry(
      final Map.Entry<String, Long> entry, final SuggestionStage staging ) {
    final String key = entry.getKey();
    long count = entry.getValue();

    if( count <= 0 ) {
      if( countThreshold > 0 ) {
        return;
      }
      count = 0L;
    }

    Long countPrevious = belowThresholdWords.get( key );

    if( countThreshold > 1 && countPrevious != null ) {

      count = (Long.MAX_VALUE - countPrevious > count) ?
          countPrevious + count : Long.MAX_VALUE;

      if( count >= countThreshold ) {
        belowThresholdWords.remove( key );
      }
      else {
        belowThresholdWords.put( key, count );
      }
    }
    else {
      if( lexiconWords.containsKey( key ) ) {
        countPrevious = lexiconWords.get( key );
        count = (Long.MAX_VALUE - countPrevious > count) ?
            countPrevious + count : Long.MAX_VALUE;
        lexiconWords.put( key, count );
        return;
      }
      else if( count < countThreshold ) {
        belowThresholdWords.put( key, count );
        return;
      }
      lexiconWords.put( key, count );

      if( key.length() > maxDictionaryWordLength ) {
        maxDictionaryWordLength = key.length();
      }
      generateDeletes( key, staging );
    }
  }

  private void generateDeletes( String key, SuggestionStage staging ) {
    Set<String> edits = editsPrefix( key );

    if( staging != null ) {
      edits.forEach( delete -> staging.add( stringHasher.hash( delete ),
                                            key ) );
    }
    else {
      edits.forEach(
          delete -> {
            long deleteHash = stringHasher.hash( delete );
            String[] suggestions = deletes.get( deleteHash );
            if( suggestions != null ) {
              var newSuggestions = Arrays.copyOf( suggestions,
                                                  suggestions.length + 1 );
              deletes.put( deleteHash, newSuggestions );
              suggestions = newSuggestions;
            }
            else {
              suggestions = new String[ 1 ];
              deletes.put( deleteHash, suggestions );
            }
            suggestions[ suggestions.length - 1 ] = key;
          } );
    }
  }

  public List<SuggestItem> lookup( String input, Verbosity verbosity )
      throws NotInitializedException {
    return lookup( input, verbosity, this.maxDictionaryEditDistance, false );
  }

  @SuppressWarnings("SameParameterValue")
  private List<SuggestItem> lookup(
      String input, Verbosity verbosity, int maxEditDistance,
      boolean includeUnknown ) throws NotInitializedException {
    if( maxEditDistance > maxDictionaryEditDistance ) {
      throw new IllegalArgumentException(
          "maxEditDistance > maxDictionaryEditDistance" );
    }

    if( lexiconWords.isEmpty() ) {
      throw new NotInitializedException(
          "There are no words in the dictionary. Please, call " +
              "`loadDictionary` to add words." );
    }

    List<SuggestItem> suggestions = new ArrayList<>();
    int inputLen = input.length();
    if( inputLen - maxEditDistance > maxDictionaryWordLength ) {
      return Collections.emptyList();
    }

    long suggestionCount;
    if( lexiconWords.containsKey( input ) ) {
      suggestions.add( new SuggestItem( input, 0, lexiconWords.get( input ) ) );
      if( !Verbosity.ALL.equals( verbosity ) ) {
        return suggestions;
      }
    }

    if( maxEditDistance == 0 ) {
      return suggestions;
    }

    Set<String> deletesAlreadyConsidered = new HashSet<>();
    Set<String> suggestionsAlreadyConsidered = new HashSet<>();

    suggestionsAlreadyConsidered.add( input );

    int maxEditDistance2 = maxEditDistance;
    int candidatePointer = 0;
    final List<String> candidates = new ArrayList<>();

    int inputPrefixLen = inputLen;
    if( inputPrefixLen > prefixLength ) {
      inputPrefixLen = prefixLength;
      candidates.add( input.substring( 0, inputPrefixLen ) );
    }
    else {
      candidates.add( input );
    }

    while( candidatePointer < candidates.size() ) {
      String candidate = candidates.get( candidatePointer++ );
      int candidateLength = candidate.length();
      int lengthDiff = inputPrefixLen - candidateLength;

      if( lengthDiff > maxEditDistance2 ) {
        if( verbosity.equals( Verbosity.ALL ) ) {
          continue;
        }
        else {
          break;
        }
      }

      String[] dictSuggestions = deletes.get( stringHasher.hash( candidate ) );
      if( dictSuggestions != null ) {
        for( String suggestion : dictSuggestions ) {
          if( suggestion != null ) {
            if( suggestion.equals( input ) ) {
              continue;
            }

            int suggestionLen = suggestion.length();

            if( (Math.abs( suggestionLen - inputLen ) > maxEditDistance2)
                || (suggestionLen < candidateLength)
                || (suggestionLen == candidateLength && !suggestion.equals(
                candidate )) ) {
              continue;
            }
            int suggestionPrefixLen = Math.min( suggestionLen, prefixLength );
            if( suggestionPrefixLen > inputPrefixLen
                && (suggestionPrefixLen - candidateLength) > maxEditDistance2 ) {
              continue;
            }

            int distance;
            int min = 0;
            if( candidateLength == 0 ) {
              distance = Math.max( inputLen, suggestionLen );
              if( distance <= maxEditDistance2 ) {
                suggestionsAlreadyConsidered.add( suggestion );
              }
            }
            else if( suggestionLen == 1 ) {
              if( input.indexOf( suggestion.charAt( 0 ) ) < 0 ) {
                distance = inputLen;
              }
              else {
                distance = inputLen - 1;
              }
              if( distance <= maxEditDistance2 ) {
                suggestionsAlreadyConsidered.add( suggestion );
              }
            }
            else {
              if( (prefixLength - maxEditDistance == candidateLength)
                  && (((min = Math.min( inputLen,
                                        suggestionLen ) - prefixLength) > 1)
                  && (!input
                  .substring( inputLen + 1 - min )
                  .equals( suggestion.substring( suggestionLen + 1 - min ) )))
                  || ((min > 0)
                  && (input.charAt( inputLen - min ) != suggestion.charAt(
                  suggestionLen - min ))
                  && ((input.charAt( inputLen - min - 1 )
                  != suggestion.charAt( suggestionLen - min ))
                  || (input.charAt( inputLen - min )
                  != suggestion.charAt( suggestionLen - min - 1 )))) ) {
                continue;
              }
              else {
                if( (!verbosity.equals( Verbosity.ALL )
                    && deleteSuggestionPrefix(
                    candidate, candidateLength, suggestion, suggestionLen ))
                    || !suggestionsAlreadyConsidered.add( suggestion ) ) {
                  continue;
                }
                distance = damerauLevenshteinOSA.distance(
                    input, suggestion, maxEditDistance2 );
                if( distance < 0 ) {
                  continue;
                }
              }

              if( distance <= maxEditDistance2 ) {
                suggestionCount = lexiconWords.get( suggestion );
                SuggestItem suggestItem = new SuggestItem(
                    suggestion, distance, suggestionCount );
                if( !suggestions.isEmpty() ) {
                  switch( verbosity ) {
                    case CLOSEST:
                      if( distance < maxEditDistance2 ) {
                        suggestions.clear();
                        break;
                      }
                      continue;
                    case TOP:
                      if( distance < maxEditDistance2
                          || suggestionCount
                          > suggestions.get( 0 )
                                       .getFrequencyOfSuggestionInDict() ) {
                        maxEditDistance2 = distance;
                        suggestions.set( 0, suggestItem );
                      }
                      continue;
                    case ALL:
                      break;
                  }
                }
                if( !verbosity.equals( ALL ) ) {
                  maxEditDistance2 = distance;
                }
                suggestions.add( suggestItem );
              }
            }
          }
        }
      }

      // add edits
      if( lengthDiff < maxEditDistance && candidateLength <= prefixLength ) {
        if( !verbosity.equals( ALL ) && lengthDiff >= maxEditDistance2 ) {
          continue;
        }
        for( int i = 0; i < candidateLength; i++ ) {
          StringBuilder editableString = new StringBuilder( candidate );
          String delete = editableString.deleteCharAt( i ).toString();
          if( deletesAlreadyConsidered.add( delete ) ) {
            candidates.add( delete );
          }
        }
      }
    }
    if( suggestions.size() > 1 ) {
      Collections.sort( suggestions );
    }
    if( includeUnknown && (suggestions.isEmpty()) ) {
      SuggestItem noSuggestionsFound = new SuggestItem(
          input, maxEditDistance + 1, 0 );
      suggestions.add( noSuggestionsFound );
    }
    return suggestions;
  }

  Map<Long, String[]> getDeletes() {
    return deletes;
  }

  public List<SuggestItem> lookupCompound( String input, int editDistanceMax )
      throws NotInitializedException {
    List<String> termList = Arrays.asList( input.split( " " ) );
    List<SuggestItem> suggestions;
    List<SuggestItem> suggestionParts = new ArrayList<>();
    EditDistance editDistance = new DamerauLevenshteinOSA();

    boolean lastCombination = false;

    for( int i = 0; i < termList.size(); i++ ) {
      suggestions = lookup(
          termList.get( i ), Verbosity.TOP, editDistanceMax, false );

      if( i > 0
          && !lastCombination
          && combineWords(
          editDistanceMax, termList, suggestions, suggestionParts, i ) ) {
        lastCombination = true;
        continue;
      }

      lastCombination = false;

      if( !suggestions.isEmpty()
          && (suggestions.get( 0 ).getEditDistance() == 0 || termList.get( i )
                                                                     .length() == 1) ) {
        suggestionParts.add( suggestions.get( 0 ) );
      }
      else {
        splitWords( editDistanceMax,
                    termList,
                    suggestions,
                    suggestionParts,
                    i );
      }
    }
    double freq = N;
    StringBuilder stringBuilder = new StringBuilder();
    for( SuggestItem suggestItem : suggestionParts ) {
      stringBuilder.append( suggestItem.getSuggestion() ).append( " " );
      freq *= suggestItem.getFrequencyOfSuggestionInDict() / N;
    }

    String term = stringBuilder.toString().stripTrailing();
    SuggestItem suggestion =
        new SuggestItem( term, editDistance.distance(
            input, term, Integer.MAX_VALUE ), freq );
    List<SuggestItem> suggestionsLine = new ArrayList<>();
    suggestionsLine.add( suggestion );
    return suggestionsLine;
  }

  private void splitWords(
      int editDistanceMax,
      List<String> termList,
      List<SuggestItem> suggestions,
      List<SuggestItem> suggestionParts,
      int i ) throws NotInitializedException {
    SuggestItem suggestionSplitBest = null;
    if( !suggestions.isEmpty() ) {
      suggestionSplitBest = suggestions.get( 0 );
    }

    String word = termList.get( i );
    if( word.length() > 1 ) {
      for( int j = 1; j < word.length(); j++ ) {
        String part1 = word.substring( 0, j );
        String part2 = word.substring( j );
        SuggestItem suggestionSplit;
        List<SuggestItem> suggestions1 = lookup(
            part1, Verbosity.TOP, editDistanceMax, false );
        if( !suggestions1.isEmpty() ) {
          List<SuggestItem> suggestions2 = lookup(
              part2, Verbosity.TOP, editDistanceMax, false );
          if( !suggestions2.isEmpty() ) {

            String splitTerm =
                suggestions1.get( 0 ).getSuggestion() + " " + suggestions2.get(
                    0 ).getSuggestion();
            int splitDistance = damerauLevenshteinOSA.distance(
                word, splitTerm, editDistanceMax );

            if( splitDistance < 0 ) {
              splitDistance = editDistanceMax + 1;
            }

            if( suggestionSplitBest != null ) {
              if( splitDistance > suggestionSplitBest.getEditDistance() ) {
                continue;
              }
              if( splitDistance < suggestionSplitBest.getEditDistance() ) {
                suggestionSplitBest = null;
              }
            }
            double freq;
            if( lexiconBigrams.containsKey( splitTerm ) ) {
              freq = lexiconBigrams.get( splitTerm );

              if( !suggestions.isEmpty() ) {
                if( (suggestions1.get( 0 )
                                 .getSuggestion() + suggestions2.get( 0 )
                                                                .getSuggestion())
                    .equals( word ) ) {
                  freq = Math.max( freq,
                                   suggestions.get( 0 )
                                              .getFrequencyOfSuggestionInDict() + 2 );
                }
                else if( (suggestions1
                    .get( 0 )
                    .getSuggestion()
                    .equals( suggestions.get( 0 ).getSuggestion() )
                    || suggestions2
                    .get( 0 )
                    .getSuggestion()
                    .equals( suggestions.get( 0 ).getSuggestion() )) ) {
                  freq = Math.max( freq,
                                   suggestions.get( 0 )
                                              .getFrequencyOfSuggestionInDict() + 1 );
                }

              }
              else if( (suggestions1.get( 0 )
                                    .getSuggestion() + suggestions2.get( 0 )
                                                                   .getSuggestion())
                  .equals( word ) ) {
                freq =
                    Math.max(
                        freq,
                        Math.max(
                            suggestions1.get( 0 )
                                        .getFrequencyOfSuggestionInDict(),
                            suggestions2.get( 0 )
                                        .getFrequencyOfSuggestionInDict() ) );
              }
            }
            else {
              // The Naive Bayes probability of the word combination is the
              // product of the two
              // word probabilities: P(AB) = P(A) * P(B)
              // use it to estimate the frequency count of the combination,
              // which then is used
              // to rank/select the best splitting variant
              freq =
                  Math.min(
                      bigramCountMin,
                      (long)
                          ((suggestions1.get( 0 )
                                        .getFrequencyOfSuggestionInDict()
                              / (double) SymSpell.N)
                              * suggestions2.get( 0 )
                                            .getFrequencyOfSuggestionInDict()) );
            }
            suggestionSplit = new SuggestItem( splitTerm, splitDistance, freq );

            if( suggestionSplitBest == null
                || suggestionSplit.getFrequencyOfSuggestionInDict()
                > suggestionSplitBest.getFrequencyOfSuggestionInDict() ) {
              suggestionSplitBest = suggestionSplit;
            }
          }
        }
      }
      if( suggestionSplitBest != null ) {
        suggestionParts.add( suggestionSplitBest );
      }
      else {
        SuggestItem suggestItem = new SuggestItem(
            word, editDistanceMax + 1,
            (long) ((double) 10 / Math.pow(
                10, word.length() )) ); //
        // estimated word occurrence probability P=10 / (N * 10^word length l)

        suggestionParts.add( suggestItem );
      }
    }
    else {
      SuggestItem suggestItem = new SuggestItem(
          word, editDistanceMax + 1,
          (long) ((double) 10 / Math.pow( 10, word.length() )) );
      suggestionParts.add( suggestItem );
    }
  }

  private boolean combineWords(
      int editDistanceMax,
      List<String> termList,
      List<SuggestItem> suggestions,
      List<SuggestItem> suggestionParts,
      int i ) throws NotInitializedException {
    List<SuggestItem> suggestionsCombination = lookup(
        termList.get( i - 1 ) + termList.get( i ),
        Verbosity.TOP,
        editDistanceMax,
        false );
    if( !suggestionsCombination.isEmpty() ) {
      SuggestItem best1 = suggestionParts.get( suggestionParts.size() - 1 );
      SuggestItem best2;
      if( !suggestions.isEmpty() ) {
        best2 = suggestions.get( 0 );
      }
      else {
        String term = termList.get( i );
        long estimatedWordOccurrenceProbability =
            (long) ((double) 10 / Math.pow( 10,
                                            term.length() )); // P=10 / (N *
        // 10^word length l)
        best2 = new SuggestItem( term,
                                 editDistanceMax + 1,
                                 estimatedWordOccurrenceProbability );
      }

      int distance = best1.getEditDistance() + best2.getEditDistance();

      SuggestItem firstSuggestion = suggestionsCombination.get( 0 );

      if( distance >= 0 && (firstSuggestion.getEditDistance() + 1 < distance)
          || (firstSuggestion.getEditDistance() + 1 == distance
          && firstSuggestion.getFrequencyOfSuggestionInDict()
          > best1.getFrequencyOfSuggestionInDict()
          / N
          * best2.getFrequencyOfSuggestionInDict()) ) {
        suggestionsCombination.set(
            0,
            new SuggestItem(
                firstSuggestion.getSuggestion(),
                firstSuggestion.getEditDistance(),
                firstSuggestion.getFrequencyOfSuggestionInDict() ) );
        suggestionParts.set( suggestionParts.size() - 1,
                             suggestionsCombination.get( 0 ) );
        return true;
      }
    }
    return false;
  }

  Map<String, Long> getWords() {
    return lexiconWords;
  }
}
