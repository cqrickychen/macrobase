package edu.stanford.futuredata.macrobase.analysis.summary.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.roaringbitmap.RoaringBitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encode every combination of attribute names and values into a distinct integer.
 * This class assumes that attributes are stored in String columns in dataframes
 * and is used inside of the explanation operators to search for explanatory
 * column values.
 */
public class AttributeEncoder {
    private Logger log = LoggerFactory.getLogger("AttributeEncoder");
    // An encoding for values which do not satisfy the minimum support threshold in encodeAttributesWithSupport.
    public static int noSupport = Integer.MAX_VALUE;
    private final int cardinalityThreshold = 50;

    private HashMap<Integer, Map<String, Integer>> encoder;
    private int nextKey;

    private HashMap<Integer, String> valueDecoder;
    private HashMap<Integer, Integer> columnDecoder;
    private List<String> colNames;
    private HashMap<Integer, RoaringBitmap>[][] bitmap;
    private ArrayList<Integer> outlierList[];
    private boolean isBitmapEncoded[];

    public AttributeEncoder() {
        encoder = new HashMap<>();
        // Keys must start at 1 because IntSetAsLong does not accept zero values.
        nextKey = 1;
        valueDecoder = new HashMap<>();
        columnDecoder = new HashMap<>();
    }
    public void setColumnNames(List<String> colNames) {
        this.colNames = colNames;
    }

    public int decodeColumn(int i) {return columnDecoder.get(i);}
    public String decodeColumnName(int i) {return colNames.get(columnDecoder.get(i));}
    public String decodeValue(int i) {return valueDecoder.get(i);}
    public HashMap<Integer, Integer> getColumnDecoder() {return columnDecoder;}
    public HashMap<Integer, RoaringBitmap>[][] getBitmap() {return bitmap;}
    public ArrayList<Integer>[] getOutlierList() {return outlierList;}
    public boolean[] getIsBitmapEncodedArray() {return isBitmapEncoded;}

    /**
     * Encodes columns giving each value which satisfies a minimum support threshold a key
     * equal to its rank among all values which satisfy that threshold (so the single most common
     * value has key 1, the next has key 2, and so on).  Encode all values not satisfying the threshold
     * as AttributeEncoder.noSupport.
     * @param columns Columns to be encoded.
     * @param minSupport Minimum support to be satisfied.
     * @param outlierColumn The ith value in this array is the number of outliers whose attributes are those of
     *                      row i of columns.
     * @return A two-dimensional array of encoded values.
     */
    public int[][] encodeAttributesWithSupport(List<String[]> columns, double minSupport,
        double[] outlierColumn, boolean useBitmaps) {
        if (columns.isEmpty()) {
            return new int[0][0];
        }

        int numColumns = columns.size();
        int numRows = columns.get(0).length;

        for (int i = 0; i < numColumns; i++) {
            if (!encoder.containsKey(i)) {
                encoder.put(i, new HashMap<>());
            }
        }
        // Create a map from strings to the number of times
        // each string appears in an outlier.
        int numOutliers = 0;
        HashMap<String, Double> countMap = new HashMap<>();
        for (int colIdx = 0; colIdx < numColumns; colIdx++) {
            String[] curCol = columns.get(colIdx);
            for (int rowIdx = 0; rowIdx < numRows; rowIdx++) {
                if (outlierColumn[rowIdx] > 0.0) {
                    if (colIdx == 0)
                        numOutliers += outlierColumn[rowIdx];
                    // prepend column index as String to column value to disambiguate
                    // between two identical values in different columns
                    String colVal = Integer.toString(colIdx) + curCol[rowIdx];
                    Double curCount = countMap.get(colVal);
                    if (curCount == null)
                        countMap.put(colVal, outlierColumn[rowIdx]);
                    else
                        countMap.put(colVal, curCount + outlierColumn[rowIdx]);
                }
            }
        }

        // Rank the strings that have minimum support among the outliers
        // by the amount of support they have.
        double minSupportThreshold = minSupport * numOutliers;
        List<String> filterOnMinSupport = countMap.keySet().stream()
                .filter(line -> countMap.get(line) >= minSupportThreshold)
                .collect(Collectors.toList());
        filterOnMinSupport.sort((s1, s2) -> countMap.get(s2).compareTo(countMap.get(s1)));

        HashMap<String, Integer> stringToRank = new HashMap<>(filterOnMinSupport.size());
        for (int i = 0; i < filterOnMinSupport.size(); i++) {
            // We must one-index ranks because IntSetAsLong does not accept zero values.
            stringToRank.put(filterOnMinSupport.get(i), i + 1);
        }

        // Encode the strings that have support with a key equal to their rank.
        int[][] encodedAttributes = new int[numRows][numColumns];
        bitmap = new HashMap[numColumns][2];
        for (int i = 0; i < numColumns; ++i) {
            for (int j = 0; j < 2; j++)
                bitmap[i][j] = new HashMap<>();
        }
        outlierList = new ArrayList[numColumns];
        for (int i = 0; i < numColumns; i++)
            outlierList[i] = new ArrayList<>();
        isBitmapEncoded = new boolean[numColumns];

        for (int colIdx = 0; colIdx < numColumns; colIdx++) {
            Map<String, Integer> curColEncoder = encoder.get(colIdx);
            String[] curCol = columns.get(colIdx);
            HashSet<Integer> foundOutliers = new HashSet<>();
            for (int rowIdx = 0; rowIdx < numRows; rowIdx++) {
                String colVal = curCol[rowIdx];
                // Again, prepend column index as String to column value to disambiguate
                // between two identical values in different columns
                String colNumAndVal = Integer.toString(colIdx) + colVal;
                int oidx = (outlierColumn[rowIdx] > 0.0) ? 1 : 0; //1 = outlier, 0 = inlier
                if (!curColEncoder.containsKey(colVal)) {
                    if (stringToRank.containsKey(colNumAndVal)) {
                        int newKey = stringToRank.get(colNumAndVal);
                        curColEncoder.put(colVal, newKey);
                        valueDecoder.put(newKey, colVal);
                        columnDecoder.put(newKey, colIdx);
                        nextKey++;
                    } else {
                        curColEncoder.put(colVal, noSupport);
                    }
                }
                int curKey = curColEncoder.get(colVal);
                encodedAttributes[rowIdx][colIdx] = curKey;

                if (oidx == 1 && curKey != noSupport && !foundOutliers.contains(curKey)) {
                    foundOutliers.add(curKey);
                    outlierList[colIdx].add(curKey);
                }
            }
            if (useBitmaps && outlierList[colIdx].size() < cardinalityThreshold) {
                isBitmapEncoded[colIdx] = true;
                for (int rowIdx = 0; rowIdx < numRows; rowIdx++) {
                    String colVal = curCol[rowIdx];
                    int oidx = (outlierColumn[rowIdx] > 0.0) ? 1 : 0; //1 = outlier, 0 = inlier
                    int curKey = curColEncoder.get(colVal);
                    if (curKey != noSupport) {
                        if (bitmap[colIdx][oidx].containsKey(curKey)) {
                            bitmap[colIdx][oidx].get(curKey).add(rowIdx);
                        } else {
                            bitmap[colIdx][oidx].put(curKey, RoaringBitmap.bitmapOf(rowIdx));
                        }
                    }
                }
            }
        }
        log.debug("Bitmap-encoded columns: {}", Arrays.toString(isBitmapEncoded));
        return encodedAttributes;
    }

    public int[][] encodeAttributesAsArray(List<String[]> columns) {
        if (columns.isEmpty()) {
            return new int[0][0];
        }

        int numColumns = columns.size();
        int numRows = columns.get(0).length;

        // No columns are bitmap encoded, all are false.
        isBitmapEncoded = new boolean[numColumns];

        for (int i = 0; i < numColumns; i++) {
            if (!encoder.containsKey(i)) {
                encoder.put(i, new HashMap<>());
            }
        }

        int[][] encodedAttributes = new int[numRows][numColumns];

        for (int colIdx = 0; colIdx < numColumns; colIdx++) {
            Map<String, Integer> curColEncoder = encoder.get(colIdx);
            String[] curCol = columns.get(colIdx);
            for (int rowIdx = 0; rowIdx < numRows; rowIdx++) {
                String colVal = curCol[rowIdx];
                if (!curColEncoder.containsKey(colVal)) {
                    curColEncoder.put(colVal, nextKey);
                    valueDecoder.put(nextKey, colVal);
                    columnDecoder.put(nextKey, colIdx);
                    nextKey++;
                }
                int curKey = curColEncoder.get(colVal);
                encodedAttributes[rowIdx][colIdx] = curKey;
            }
        }

        return encodedAttributes;
    }

    /**
     * TODO
     * encode Primary Key and Values as row-based
     * @param foreignKeys
     * @param primaryKeyAndValues
     * @param encodedPrimaryKeyAndValues
     */
    public List<int[]> encodeKeyValueAttributes(final List<String[]> foreignKeys,
        final List<String[]> primaryKeyAndValues, int[][] encodedPrimaryKeyAndValues) {
        final Builder<int[]> builder = ImmutableList.builder();
        if (foreignKeys.isEmpty() && primaryKeyAndValues.isEmpty()) {
            return builder.build();
        }
        final int numKeys = foreignKeys.size() + 1; // add one for primary key
        final int numColumns = numKeys + primaryKeyAndValues.size() - 1;
        // one decoder for all the key columns
        final HashMap<String, Integer> keyDecoder = new HashMap<>();
        for (int i = 0; i < numKeys; i++) {
            encoder.put(i, keyDecoder);
        }
        // a decoder for each value column
        for (int i = numKeys; i < numColumns; i++) {
            encoder.put(i, new HashMap<>());
        }

        int colIdx = 0;
        for (String[] curCol : foreignKeys) {
            final Map<String, Integer> curColEncoder = encoder.get(colIdx);
            final int[] encodedCol = new int[curCol.length];
            int rowIdx = 0;
            for (String colVal : curCol) {
                //noinspection Duplicates
                if (!curColEncoder.containsKey(colVal)) {
                    curColEncoder.put(colVal, nextKey);
                    valueDecoder.put(nextKey, colVal);
                    columnDecoder.put(nextKey, colIdx);
                    nextKey++;
                }
                int curKey = curColEncoder.get(colVal);
                encodedCol[rowIdx] = curKey;
                ++rowIdx;
            }
            builder.add(encodedCol);
            ++colIdx;
        }

        if (primaryKeyAndValues.isEmpty()) {
            return builder.build();
        }

        final int numRows = encodedPrimaryKeyAndValues[0].length;
        for (String[] curCol : primaryKeyAndValues) {
            Map<String, Integer> curColEncoder = encoder.get(colIdx);
            final int[] encodedColumn = encodedPrimaryKeyAndValues[colIdx - numKeys + 1];
            for (int rowIdx = 0; rowIdx < numRows; rowIdx++) {
                String colVal = curCol[rowIdx];
                //noinspection Duplicates
                if (!curColEncoder.containsKey(colVal)) {
                    curColEncoder.put(colVal, nextKey);
                    valueDecoder.put(nextKey, colVal);
                    columnDecoder.put(nextKey, colIdx);
                    nextKey++;
                }
                encodedColumn[rowIdx] = curColEncoder.get(colVal);
            }
            ++colIdx;
        }
        return builder.build();
    }

    public List<int[]> encodeAttributes(List<String[]> columns) {
        if (columns.isEmpty()) {
            return new ArrayList<>();
        }

        int[][] encodedArray = encodeAttributesAsArray(columns);
        int numRows = columns.get(0).length;

        ArrayList<int[]> encodedAttributes = new ArrayList<>(numRows);
        for (int i = 0; i < numRows; i++) {
            encodedAttributes.add(encodedArray[i]);
        }

        return encodedAttributes;
    }

    public List<Set<Integer>> encodeAttributesAsSets(List<String[]> columns) {
        List<int[]> arrays = encodeAttributes(columns);
        ArrayList<Set<Integer>> sets = new ArrayList<>(arrays.size());
        for (int[] row : arrays) {
            HashSet<Integer> curSet = new HashSet<>(row.length);
            for (int i : row) {
                curSet.add(i);
            }
            sets.add(curSet);
        }
        return sets;
    }

    public int getNextKey() {
        return nextKey;
    }

    public Map<String, String> decodeSet(Set<Integer> set) {
        HashMap<String, String> m = new HashMap<>(set.size());
        for (int i : set) {
            m.put(decodeColumnName(i), decodeValue(i));
        }
        return m;
    }

}
