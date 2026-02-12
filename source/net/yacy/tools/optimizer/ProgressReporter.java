package net.yacy.tools.optimizer;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Progress reporter with nice formatting and ETA calculation
 */
public class ProgressReporter {

    private int currentPhase;
    private String currentPhaseTitle;
    private long phaseStartTime;
    private long totalStartTime;
    private double phaseProgress; // 0.0 to 1.0
    private String phaseDetails = "";
    private int progressBarWidth = 40;

    public ProgressReporter() {
        this.totalStartTime = System.currentTimeMillis();
    }

    public void startPhase(int phase, String title) {
        this.currentPhase = phase;
        this.currentPhaseTitle = title;
        this.phaseStartTime = System.currentTimeMillis();
        this.phaseProgress = 0.0;
        this.phaseDetails = "";
        
        System.out.println("\n[" + phase + "/5] " + title);
    }

    public void updateProgress(double progress, String details) {
        this.phaseProgress = Math.min(1.0, Math.max(0.0, progress));
        this.phaseDetails = details != null ? details : "";
        printProgressBar();
    }

    public void updateProgress(double progress) {
        updateProgress(progress, "");
    }

    public void completePhase(String details) {
        this.phaseProgress = 1.0;
        this.phaseDetails = details != null ? details : "";
        printProgressBar();
    }

    private void printProgressBar() {
        long elapsed = System.currentTimeMillis() - phaseStartTime;
        String progressBar = buildProgressBar();
        int percent = (int) (phaseProgress * 100);
        
        String eta = "";
        if (phaseProgress > 0 && phaseProgress < 1.0) {
            long estimatedTotal = (long) (elapsed / phaseProgress);
            long estimatedRemaining = estimatedTotal - elapsed;
            eta = " | " + formatDuration(estimatedRemaining) + " remaining";
        } else if (phaseProgress == 1.0) {
            eta = " | " + formatDuration(elapsed);
        }

        System.out.print("\r      ");
        System.out.print(progressBar);
        System.out.print(" " + String.format("%3d%%", percent));
        System.out.print(eta);
        if (!phaseDetails.isEmpty()) {
            System.out.print("\n      " + phaseDetails + "\r");
        }
        System.out.flush();
    }

    private String buildProgressBar() {
        int filled = (int) (phaseProgress * progressBarWidth);
        int empty = progressBarWidth - filled;
        
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < filled; i++) {
            sb.append("█");
        }
        for (int i = 0; i < empty; i++) {
            sb.append("░");
        }
        sb.append("]");
        return sb.toString();
    }

    public void info(String message) {
        System.out.println("\n      ℹ " + message);
    }

    public void error(String message) {
        System.out.println("\n      ✗ ERROR: " + message);
    }

    public void success(String message) {
        System.out.println("\n      ✓ " + message);
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%ds", seconds);
        }
    }

    public long getTotalElapsedTime() {
        return System.currentTimeMillis() - totalStartTime;
    }
}
