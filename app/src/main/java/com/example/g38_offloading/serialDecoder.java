package com.example.g38_offloading;

import java.io.Serializable;

public class serialDecoder implements Serializable {
    /*
    This helps in serialization of the data that needs to be sent the slave by master
    Master will send an object of Proxy request to slave which has 1 Row of matrix A, n*n matrix B and row number
    On slave receiving this object will calculate the matrix multiplication and returns the row vector along with row number
    */
    private int[] a;
    private int[][] b;
    private int row;

    //constructor
    public serialDecoder(int[] a, int[][] b, int row) {
        this.setA(a);
        this.setB(b);
        this.setRow(row);
    }

    //getter methods
    public int[] getA() {
        return a;
    }

    public void setA(int[] a) {
        this.a = a;
    }

    public int[][] getB() {
        return b;
    }

    //setter methods
    public void setB(int[][] b) {
        this.b = b;
    }

    public int getRow() {
        return row;
    }
//    public int[] geta() {
//        return a;
//    }
//    public int[][] getb() {
//        return b;
//    }
    public void setRow(int row) {
        this.row = row;
    }
}
