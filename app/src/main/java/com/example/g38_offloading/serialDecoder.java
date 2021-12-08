package com.example.g38_offloading;

import java.io.Serializable;

// serialing encoder
public class serialDecoder implements Serializable {
    private static final long serialVersionUID = 6529685098267757690L;
    private int[] rowResult;
    private int row;
    private String deviceName;

    public serialDecoder(int[] rowResult, int row, String deviceName) {
        this.setrowResult(rowResult);
        this.setRow(row);
        this.setDeviceName(deviceName);
    }

    public void setrowResult(int[] rowResult) {
        this.rowResult = rowResult;
    }

    public void setRow(int row) {
        this.row = row;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }
}
