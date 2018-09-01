///////////////////////////////////////////////////////////////////////////////
// For information as to what this class does, see the Javadoc, below.       //
// Copyright (C) 1998, 1999, 2000, 2001, 2002, 2003, 2004, 2005, 2006,       //
// 2007, 2008, 2009, 2010, 2014, 2015 by Peter Spirtes, Richard Scheines, Joseph   //
// Ramsey, and Clark Glymour.                                                //
//                                                                           //
// This program is free software; you can redistribute it and/or modify      //
// it under the terms of the GNU General Public License as published by      //
// the Free Software Foundation; either version 2 of the License, or         //
// (at your option) any later version.                                       //
//                                                                           //
// This program is distributed in the hope that it will be useful,           //
// but WITHOUT ANY WARRANTY; without even the implied warranty of            //
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the             //
// GNU General Public License for more details.                              //
//                                                                           //
// You should have received a copy of the GNU General Public License         //
// along with this program; if not, write to the Free Software               //
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA //
///////////////////////////////////////////////////////////////////////////////

package edu.cmu.tetrad.search;

import edu.cmu.tetrad.data.DataSet;
import edu.cmu.tetrad.graph.Node;
import edu.cmu.tetrad.util.RandomUtil;
import edu.cmu.tetrad.util.StatUtils;
import org.apache.commons.math3.distribution.NormalDistribution;

import java.util.*;

import static edu.cmu.tetrad.util.StatUtils.*;
import static java.lang.Math.*;
import static java.lang.Math.pow;

/**
 * Checks conditional independence of variable in a continuous data set using Daudin's method. See
 * <p>
 * Ramsey, J. D. (2014). A scalable conditional independence test for nonlinear, non-Gaussian data. arXiv
 * preprint arXiv:1401.5031.
 * <p>
 * This is corrected using Lemma 2, condition 4 of
 * <p>
 * Zhang, K., Peters, J., Janzing, D., & Schölkopf, B. (2012). Kernel-based conditional independence test and
 * application in causal discovery. arXiv preprint arXiv:1202.3775.
 * <p>
 * This all follows the original Daudin paper, which is this:
 * <p>
 * Daudin, J. J. (1980). Partial association measures and a  application to qualitative regression.
 * Biometrika, 67(3), 581-590.
 * <p>
 * We use Nadaraya-Watson kernel regression, though we further restrict the sample size to nearby points.
 *
 * @author Joseph Ramsey
 */
public final class ConditionalCorrelationIndependence {

    public enum Kernel {Epinechnikov, Gaussian}

    public enum Basis {Polynomial, Cosine}

    /**
     * The matrix of data, N x M, where N is the number of samples, M the number
     * of variables, gotten from dataSet.
     */
    private double[][] data;

    /**
     * The ith array gives indices into the ith variables in sorted order.
     */
    private final ArrayList<List<Integer>> sortedIndices;

    /**
     * The significance level of the independence tests.
     */
    private double alpha;

    /**
     * Bowman and Azzalini Kernel widths of each variable.
     */
    private double[] h;

    /**
     * Looks up the index of a record in the the sorted order for each variable z.
     */
    private List<Map<Integer, Integer>> reverseLookup;

    /**
     * Depth 0 residuals for reuse.
     */
    private double[][] depth0Residuals;

    /**
     * The q value of the most recent test.
     */
    private double score;

    /**
     * Map from nodes to the indices.
     */
    private Map<String, Integer> indices;

    /**
     * Number of functions to use in the (truncated) basis.
     */
    private int numFunctions = 10;

    /**
     * Z cutoff for testing; depends on alpha.
     */
    private double cutoff;

    /**
     * Azzalini kernel widths are multiplied by this.
     */
    private double width = 1.0;

    /**
     * Kernel
     */
    private Kernel kernelMultiplier = Kernel.Gaussian;

    /**
     * Basis
     */
    private Basis basis = Basis.Polynomial;

    /**
     * True if the test should return false as soon as a dependency is found. Should not be used if CCI is to
     * be used as a score.
     */
    private boolean earlyReturn = true;

    /**
     * The minimum sample size to use for the kernel regression.
     */
    private int minimumSamplesize = 20;

    //==================CONSTRUCTORS====================//

    /**
     * Constructs a new Independence test which checks independence facts based on the
     * correlation data implied by the given data set (must be continuous). The given
     * significance level is used.
     *
     * @param dataSet A data set containing only continuous columns.
     * @param alpha   The alpha level of the test.
     */
    public ConditionalCorrelationIndependence(DataSet dataSet, double alpha) {
        if (dataSet == null) throw new NullPointerException();
        this.alpha = alpha;
        this.data = dataSet.getDoubleData().transpose().toArray();

        for (int j = 0; j < data.length; j++) {
            data[j] = scale(data[j]);
        }

        List<Node> variables = dataSet.getVariables();

        indices = new HashMap<>();

        for (int i = 0; i < variables.size(); i++) {
            indices.put(variables.get(i).toString(), i);
        }

        h = new double[dataSet.getNumColumns()];
        double sum = 0.0;
        int count = 0;

        for (int i = 0; i < dataSet.getNumColumns(); i++) {
            h[i] = h(dataSet.getVariables().get(i).toString());

            if (h[i] != 0) {
                sum += h[i];
                count++;
            }
        }

        double avg = sum / count;

        for (int i = 0; i < h.length; i++) {
            if (h[i] == 0) h[i] = avg;
        }

        this.cutoff = getZForAlpha(alpha);

        sortedIndices = new ArrayList<>();

        for (double[] x : data) {
            List<Integer> sorted = new ArrayList<>();
            for (int t = 0; t < x.length; t++) sorted.add(t);

            sorted.sort(Comparator.comparingDouble(o -> x[o]));
            sortedIndices.add(sorted);
        }

        double[] means = new double[data.length];

        for (int r = 0; r < data.length; r++) {
            means[r] = mean(data[r]);
        }

        depth0Residuals = new double[data.length][];

        for (int z = 0; z < data.length; z++) {
            depth0Residuals[z] = new double[data[0].length];

            for (int i = 0; i < data[0].length; i++) {
                depth0Residuals[z][i] = data[z][i] - means[z];
            }
        }

        reverseLookup = new ArrayList<>();

        for (int z2 = 0; z2 < data.length; z2++) {
            Map<Integer, Integer> m = new HashMap<>();

            for (int j = 0; j < data[z2].length; j++) {
                m.put(sortedIndices.get(z2).get(j), j);
            }

            reverseLookup.add(m);
        }
    }

    private double p;

    //=================PUBLIC METHODS====================//

    /**
     * @return true iff x is independent of y conditional on z.
     */
    public double isIndependent(String x, String y, List<String> z) {
        double[] rx = residuals(x, z);
        double[] ry = residuals(y, z);

//        double[] logrx = logColumn(rx);
//        double[] logry = logColumn(ry);

        this.score = Double.NEGATIVE_INFINITY;

        double p1 = independent(rx, ry);
        double p = p1;

        if (p1 > alpha) {
            return p1;
        }

//        final double p2 = independent(rx, logry);
//        p = max(p, p2);
//
//        if (p2 > alpha) {
//            return p2;
//        }
//
//        final double p3 = independent(logrx, ry);
//        p = max(p, p3);
//
//        if (p3 > alpha) {
//            return p3;
//        }
//
//        final double p4 = independent(logrx, logry);
//        p = max(p, p3);
//
//        if (p4 > alpha) {
//            return p4;
//        }
//
//        p = max(p, p4);


        this.p = p;

//        final boolean independent = independent(rx, ry) > cutoff || independent(rx, logry) > cutoff
//                || independent(logrx, ry) > cutoff || independent(logrx, logry) > cutoff;

//        boolean okx = false;
//
//        for (String _z : z) {
//            if (independent(rx, data[indices.get(_z)]) > alpha || independent(logrx, data[indices.get(_z)]) > alpha) {
//                okx = true;
//                break;
//            }
//        }
//
//        boolean oky = false;
//
//        for (String _z : z) {
//            if (independent(ry, data[indices.get(_z)]) > alpha || independent(logry, data[indices.get(_z)]) > alpha) {
//                oky = true;
//                break;
//            }
//        }
//
//
//

        final int N = data[0].length;

        int[] _z = new int[z.size()];

        for (int m = 0; m < z.size(); m++) {
            _z[m] = indices.get(z.get(m));
        }

        if (z.isEmpty()) {
            return p;
        } else {
            double _p = 1.0;

            for (int i = 0; i < 20; i++) {
                List<Integer> js = new ArrayList<>(getCloseZs(data, _z, RandomUtil.getInstance().nextInt(N), 100));

                double[] rx2 = new double[js.size()];
                double[] ry2 = new double[js.size()];


                for (int k = 0; k < js.size(); k++) {
                    rx2[k] = rx[js.get(k)];
                    ry2[k] = ry[js.get(k)];
                }

                double pj = independent(rx2, ry2);

                if (pj < alpha) {
                    return pj;
                } else {
                    _p = pj;
                }
            }

            return _p;
        }
    }

    private double[] logColumn(double[] f) {
        double[] ret = new double[f.length];
        double min = min(f) - 0.0001;
        if (min > 0) min = 0;
        for (int i = 0; i < f.length; i++) ret[i] = log(f[i] - min);
        return ret;
    }

    /**
     * Calculates the residuals of x regressed nonparametrically onto z. Left public
     * so it can be accessed separately.
     *
     * @return a double[2][] array. The first double[] array contains the residuals for x
     * and the second double[] array contains the resituls for y.
     */
    public double[] residuals(String x, List<String> z) {
        int N = data[0].length;

        int _x = indices.get(x);

        double[] residualsx = new double[N];

        double[] xdata = Arrays.copyOf(data[_x], data[_x].length);

        double[] sumx = new double[N];

        double[] totalWeightx = new double[N];

        int[] _z = new int[z.size()];

        for (int m = 0; m < z.size(); m++) {
            _z[m] = indices.get(z.get(m));
        }

        double h = getH(_z);

        if (z.isEmpty()) {
            return depth0Residuals[indices.get(x)];
        }

        for (int i = 0; i < N; i++) {
            Set<Integer> js = getCloseZs(data, _z, i, minimumSamplesize);

            for (int j : js) {
                double xj = xdata[j];
                double d = distance(data, _z, i, j);

                double k;

                if (getKernelMultiplier() == Kernel.Epinechnikov) {
                    k = kernelEpinechnikov(d, h);
                } else if (getKernelMultiplier() == Kernel.Gaussian) {
                    k = kernelGaussian(d, h);
                } else {
                    throw new IllegalStateException("Unsupported kernel type: " + getKernelMultiplier());
                }

                sumx[i] += k * xj;
                totalWeightx[i] += k;
            }
        }

        for (int i = 0; i < N; i++) {
            if (totalWeightx[i] == 0) totalWeightx[i] = 1;

            residualsx[i] = xdata[i] - sumx[i] / totalWeightx[i];

            if (Double.isNaN(residualsx[i])) {
                residualsx[i] = 0;
            }
        }

        return residualsx;
    }

    /**
     * Number of functions to use in (truncated) basis
     */
    public int getNumFunctions() {
        return numFunctions;
    }

    public void setNumFunctions(int numFunctions) {
        this.numFunctions = numFunctions;
    }

    public Kernel getKernelMultiplier() {
        return kernelMultiplier;
    }

    public void setKernelMultiplier(Kernel kernelMultiplier) {
        this.kernelMultiplier = kernelMultiplier;
    }

    public void setBasis(Basis basis) {
        this.basis = basis;
    }

    public double getWidth() {
        return width;
    }

    public void setWidth(double width) {
        this.width = width;
    }

    public double getPValue() {
        return this.p;
    }

    /**
     * @return the minimal scores value calculated by the method for the most
     * recent independence check.
     */
    public double getScore() {
        return score - cutoff;
    }

    public void setAlpha(double alpha) {
        this.alpha = alpha;
        this.cutoff = getZForAlpha(alpha);
    }

    public double getAlpha() {
        return alpha;
    }

    public void setMinimumSamplesize(int minimumSamplesize) {
        this.minimumSamplesize = minimumSamplesize;
    }

    public void setEarlyReturn(boolean earlyReturn) {
        this.earlyReturn = earlyReturn;
    }

    //=====================PRIVATE METHODS====================//

    /**
     * @return true just in the case the x and y vectors are independent,
     * once undefined values have been removed. Left public so it can be
     * accessed separately.
     */
    private double independent(double[] x, double[] y) {

        // Can't reuse these--parallelization
        double[] _x = new double[x.length];
        double[] _y = new double[y.length];

        double maxScore = Double.NEGATIVE_INFINITY;

        for (int m = 1; m <= getNumFunctions(); m++) {
            for (int n = 1; n <= getNumFunctions(); n++) {
                for (int i = 0; i < x.length; i++) {
                    _x[i] = function(m, x[i]);
                    _y[i] = function(n, y[i]);
                }

                final double score = abs(nonparametricFisherZ(_x, _y));
                if (Double.isInfinite(score) || Double.isNaN(score)) continue;

                if (earlyReturn && maxScore >= cutoff) {
                    this.score = maxScore;
                    return 2.0 * (1.0 - new NormalDistribution(0, 1).cumulativeProbability(maxScore));
                }

                if (score > maxScore) {
                    maxScore = score;
                }
            }
        }

        if (maxScore > this.score) {
            this.score = maxScore;
        }

        return 2.0 * (1.0 - new NormalDistribution(0, 1).cumulativeProbability(maxScore));
    }

    private double[] scale(double[] x) {
        double max = StatUtils.max(x);
        double min = StatUtils.min(x);

        for (int i = 0; i < x.length; i++) {
            x[i] = min + (x[i] - min) / (max - min);
        }

        return x;
    }

    private double nonparametricFisherZ(double[] _x, double[] _y) {

        // Testing the hypothesis that _x and _y are uncorrelated and assuming that 4th moments of _x and _y
        // are finite and that the sample is large.
        double[] __x = standardize(_x);
        double[] __y = standardize(_y);

        double r = covariance(__x, __y); // correlation
        int N = __x.length;

        // Non-parametric Fisher Z test.
        double z = 0.5 * sqrt(N) * (log(1.0 + r) - log(1.0 - r));

        return z / (sqrt((moment22(__x, __y))));
    }

    private double moment22(double[] x, double[] y) {
        int N = x.length;
        double sum = 0.0;

        for (int j = 0; j < x.length; j++) {
            sum += x[j] * x[j] * y[j] * y[j];
        }

        return sum / N;
    }

    private double function(int index, double x) {
        if (basis == Basis.Polynomial) {
            double g = 1.0;

            for (int i = 1; i <= index; i++) {
                g *= x;
            }

            if (abs(g) == Double.POSITIVE_INFINITY) g = Double.NaN;

            return g;
        } else if (basis == Basis.Cosine) {
            int i = (index + 1) / 2;

            if (index % 2 == 1) {
                return sin(i * x);
            } else {
                return cos(i * x);
            }
        } else {
            throw new IllegalStateException("That basis is not configured: " + basis);
        }
    }

    // Optimal bandwidth qsuggested by Bowman and Bowman and Azzalini (1997) q.31,
    // using MAD.
    private double h(String x) {
        double[] xCol = data[indices.get(x)];
        double[] g = new double[xCol.length];
        double median = median(xCol);
        for (int j = 0; j < xCol.length; j++) g[j] = abs(xCol[j] - median);
        double mad = median(g);
        return (1.4826 * mad) * pow((4.0 / 3.0) / xCol.length, 0.2);
    }

    private double kernelEpinechnikov(double z, double h) {
        z /= getWidth() * h;
        if (abs(z) > 1) return 0.0;
        else return (/*0.75 **/ (1.0 - z * z));
    }

    private double kernelGaussian(double z, double h) {
        z /= getWidth() * h;
        return Math.exp(-z * z);
    }

    // Euclidean distance.
    private double distance(double[][] data, int[] z, int i, int j) {
        double sum = 0.0;

        for (int _z : z) {
            double d = (data[_z][i] - data[_z][j]) / 2.0;

            if (!Double.isNaN(d)) {
                sum += d * d;
            }
        }

        return sqrt(sum);
    }

    // Standardizes the given data array. No need to make a copy here.
    private double[] standardize(double[] data) {
        double sum = 0.0;

        for (double d : data) {
            sum += d;
        }

        double mean = sum / data.length;

        for (int i = 0; i < data.length; i++) {
            data[i] = data[i] - mean;
        }

        double var = 0.0;

        for (double d : data) {
            var += d * d;
        }

        var /= (data.length);
        double sd = sqrt(var);

        for (int i = 0; i < data.length; i++) {
            data[i] /= sd;
        }

        return data;
    }

    private Set<Integer> getCloseZs(double[][] data, int[] _z, int i, int minimumSamplesize) {
        Set<Integer> js = new HashSet<>();

        if (minimumSamplesize > data[0].length) throw new IllegalArgumentException("Minimum sample size exceeds the " +
                "smaple size: " + minimumSamplesize);
        if (_z.length == 0) return new HashSet<>();

        int radius = 0;

        while (js.size() < minimumSamplesize) {
            for (int z1 : _z) {
                int q = reverseLookup.get(z1).get(i);

                if (q - radius >= 0 && q - radius < data[z1].length) {
                    final int r2 = sortedIndices.get(z1).get(q - radius);
                    js.add(r2);
                }

                if (q + radius >= 0 && q + radius < data[z1].length) {
                    final int r2 = sortedIndices.get(z1).get(q + radius);
                    js.add(r2);
                }
            }

            radius++;
        }
        return js;
    }

    private double getH(int[] _z) {
        double h = 0.0;

        for (int c : _z) {
            if (this.h[c] > h) {
                h = this.h[c];
            }
        }

        h *= sqrt(_z.length);
        if (h == 0) h = 1;
        return h;
    }
}



