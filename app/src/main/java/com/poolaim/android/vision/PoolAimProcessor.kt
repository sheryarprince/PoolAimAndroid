package com.poolaim.android.vision

import com.poolaim.android.capture.CapturedRgbaFrame
import com.poolaim.android.engine.FloatPoint
import com.poolaim.android.engine.GhostBallSolver
import com.poolaim.android.engine.ShotSolution

data class ProcessedPoolFrame(
    val scene: PoolScene,
    val solutions: List<ShotSolution>,
    val diagnostic: String,
    val gameAimGuides: List<FloatPoint> = emptyList()
)

class PoolAimProcessor(
    private val detector: PoolScreenDetector = PoolScreenDetector(),
    private val solver: GhostBallSolver = GhostBallSolver(),
    private val gameAimGuideDetector: GameAimGuideDetector = GameAimGuideDetector()
) {
    fun process(frame: CapturedRgbaFrame): ProcessedPoolFrame {
        val scene = detector.detect(frame)
        val table = scene.table
            ?: return ProcessedPoolFrame(scene, emptyList(), scene.diagnostic)
        val cueCandidates = scene.balls.filter {
            it.ballId == BallIdentity.CUE && it.classificationConfidence >= 0.65f
        }
        if (cueCandidates.isEmpty()) {
            return ProcessedPoolFrame(
                scene, emptyList(),
                "${scene.diagnostic}; waiting for cue ball and in-game white aiming guides"
            )
        }
        val selectedCue = cueCandidates.maxByOrNull { it.classificationConfidence }!!
        val cleaned = if (cueCandidates.size > 1) {
            scene.copy(balls = scene.balls.filter {
                it.ballId != BallIdentity.CUE || it === selectedCue
            })
        } else scene

        // The extension trigger is exclusively the game's own white guide line.
        // No cue-stick appearance/colour or table-felt colour is used here.
        val guides = gameAimGuideDetector.detect(
            frame, selectedCue.center, selectedCue.radius, table
        )
        val solutions = solver.solve(cleaned)
        val diagnostic = when {
            guides.isNotEmpty() -> "${cleaned.diagnostic}; ${guides.size} in-game white aiming guide(s) detected"
            solutions.isNotEmpty() -> "${cleaned.diagnostic}; no in-game white aiming guide visible"
            cleaned.balls.none { it.ballId?.let { id -> id in 1..15 } == true } ->
                "${cleaned.diagnostic}; no numbered object balls identified"
            else -> "${cleaned.diagnostic}; no collision-clear shot found; waiting for white aiming guide"
        }
        return ProcessedPoolFrame(cleaned, solutions, diagnostic, guides)
    }
}
