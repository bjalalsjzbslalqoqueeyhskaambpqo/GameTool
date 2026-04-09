package com.game.net;

import com.game.game.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class SpawnCalculator {
    private static final int TILE = 48;
    private final Random rng;

    public SpawnCalculator(long seed) {
        this.rng = new Random(seed);
    }

    public List<float[]> generate(int count) {
        int players = Math.max(1, count);
        List<int[]> candidates = new ArrayList<>();

        for (int r = 1; r < Map.getRows() - 1; r++) {
            for (int c = 1; c < Map.getCols() - 1; c++) {
                if (Map.isWall(c, r)) continue;
                candidates.add(new int[]{c, r});
            }
        }

        if (candidates.isEmpty()) {
            List<float[]> fallback = new ArrayList<>();
            fallback.add(new float[]{TILE * 1.5f, TILE * 1.5f});
            return fallback;
        }

        Collections.shuffle(candidates, rng);

        List<int[]> chosen = new ArrayList<>();
        int minDistTiles = 10;
        while (chosen.size() < players && minDistTiles >= 0) {
            chosen.clear();
            for (int[] cell : candidates) {
                if (isFarEnough(cell, chosen, minDistTiles)) {
                    chosen.add(cell);
                    if (chosen.size() == players) break;
                }
            }
            minDistTiles -= 2;
        }

        while (chosen.size() < players && chosen.size() < candidates.size()) {
            int[] next = candidates.get(chosen.size());
            chosen.add(next);
        }

        List<float[]> result = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            int[] cell = chosen.get(i);
            result.add(new float[]{cell[0] * TILE + TILE * 0.5f,
                cell[1] * TILE + TILE * 0.5f});
        }
        return result;
    }

    private boolean isFarEnough(int[] cell, List<int[]> selected,
            int minDistTiles) {
        if (minDistTiles <= 0) return true;
        int minSq = minDistTiles * minDistTiles;
        for (int[] s : selected) {
            int dx = cell[0] - s[0];
            int dy = cell[1] - s[1];
            if (dx * dx + dy * dy < minSq) return false;
        }
        return true;
    }
}
