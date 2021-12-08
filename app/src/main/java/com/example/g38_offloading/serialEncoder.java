package com.example.g38_offloading;

import java.io.Serializable;

public class serialEncoder implements Serializable {

    private int[] a;
    private int[][] b;
    private int row;

    public serialEncoder(int[] a, int[][] b, int row) {
        this.setA(a);
        this.setB(b);
        this.setRow(row);
    }

    public int[] getA() {
        return a;
    }

    public void setA(int[] a) {
        this.a = a;
    }

    public int[][] getB() {
        return b;
    }

    public void setB(int[][] b) {
        this.b = b;
    }

    public int getRow() {
        return row;
    }

    public void setRow(int row) {
        this.row = row;
    }
}
