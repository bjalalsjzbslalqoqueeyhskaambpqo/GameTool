package com.game.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

public class Map {
    public static final int TILE = 64;
    public static final int COLS = 41;
    public static final int ROWS = 41;

    private static final int[][] grid = new int[ROWS][COLS];
    private static boolean generated = false;
    private static int spawnCol = 1;
    private static int spawnRow = 1;

    // Direcciones: arriba, derecha, abajo, izquierda
    private static final int[] DX = {0, 2, 0, -2};
    private static final int[] DY = {-2, 0, 2, 0};

    public static void generate(long seed) {
        Random rng = new Random(seed);

        // Todo empieza como pared
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                grid[r][c] = 1;

        // Recursive backtracking desde celda (1,1)
        carve(1, 1, rng);

        // Asegurar borde exterior todo pared
        for (int c = 0; c < COLS; c++) {
            grid[0][c] = 1;
            grid[ROWS-1][c] = 1;
        }
        for (int r = 0; r < ROWS; r++) {
            grid[r][0] = 1;
            grid[r][COLS-1] = 1;
        }

        spawnCol = 1;
        spawnRow = 1;
        generated = true;
    }

    private static void carve(int col, int row, Random rng) {
        grid[row][col] = 0; // marcar como pasillo

        // Mezclar direcciones aleatoriamente
        ArrayList<Integer> dirs = new ArrayList<>();
        dirs.add(0); dirs.add(1); dirs.add(2); dirs.add(3);
        Collections.shuffle(dirs, rng);

        for (int dir : dirs) {
            int nc = col + DX[dir];
            int nr = row + DY[dir];

            // Verificar que el destino esté dentro y sea pared
            if (nr > 0 && nr < ROWS-1 && nc > 0 && nc < COLS-1
                    && grid[nr][nc] == 1) {
                // Abrir la pared intermedia
                grid[row + DY[dir]/2][col + DX[dir]/2] = 0;
                carve(nc, nr, rng);
            }
        }
    }

    public static void ensureGenerated() {
        if (!generated) generate(System.currentTimeMillis());
    }

    public static int getRows() { ensureGenerated(); return ROWS; }
    public static int getCols() { ensureGenerated(); return COLS; }

    public static boolean isWall(int col, int row) {
        ensureGenerated();
        if (row < 0 || row >= ROWS || col < 0 || col >= COLS)
            return true;
        return grid[row][col] == 1;
    }

    public static boolean isWallAt(float worldX, float worldY) {
        ensureGenerated();
        int col = (int)(worldX / TILE);
        int row = (int)(worldY / TILE);
        return isWall(col, row);
    }

    // Spawn seguro en primer pasillo
    public static float getSpawnX() {
        ensureGenerated();
        return spawnCol * TILE + TILE * 0.5f;
    }
    public static float getSpawnY() {
        ensureGenerated();
        return spawnRow * TILE + TILE * 0.5f;
    }
}
