package com.anurag.smartdesk.model;

import java.io.Serializable;

// Represents a coordinate in the office layout grid, used for desk positioning.
public record DeskCoordinate(int row, int column) implements Serializable {
    public int squaredDistanceTo(DeskCoordinate other) {
        int dRow = this.row - other.row();
        int dCol = this.column - other.column();
        return (dRow * dRow) + (dCol * dCol);
    }
}
