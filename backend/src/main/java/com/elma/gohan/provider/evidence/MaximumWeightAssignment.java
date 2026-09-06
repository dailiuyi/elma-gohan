package com.elma.gohan.provider.evidence;

import java.util.Arrays;

/** Rectangular Hungarian assignment. Caller supplies one zero-weight dummy column per row. */
public final class MaximumWeightAssignment {
    private MaximumWeightAssignment() { }
    public static int[] solve(double[][] weights) {
        int n = weights.length;
        if (n == 0) return new int[0];
        int m = weights[0].length;
        double[] u = new double[n + 1], v = new double[m + 1];
        int[] p = new int[m + 1], way = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] min = new double[m + 1];
            Arrays.fill(min, Double.POSITIVE_INFINITY);
            boolean[] used = new boolean[m + 1];
            do {
                used[j0] = true;
                int i0 = p[j0], j1 = 0;
                double delta = Double.POSITIVE_INFINITY;
                for (int j = 1; j <= m; j++) if (!used[j]) {
                    double cur = -weights[i0 - 1][j - 1] - u[i0] - v[j];
                    if (cur < min[j]) { min[j] = cur; way[j] = j0; }
                    if (min[j] < delta) { delta = min[j]; j1 = j; }
                }
                for (int j = 0; j <= m; j++) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta; }
                    else min[j] -= delta;
                }
                j0 = j1;
            } while (p[j0] != 0);
            do { int j1 = way[j0]; p[j0] = p[j1]; j0 = j1; } while (j0 != 0);
        }
        int[] result = new int[n];
        for (int j = 1; j <= m; j++) if (p[j] != 0) result[p[j] - 1] = j - 1;
        return result;
    }
    public static double total(double[][] weights, int[] assignment) {
        double sum = 0;
        for (int i = 0; i < assignment.length; i++) sum += weights[i][assignment[i]];
        return sum;
    }
}
