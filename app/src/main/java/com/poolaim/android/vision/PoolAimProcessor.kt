package com.poolaim.android.vision

import com.poolaim.android.capture.CapturedRgbaFrame
import com.poolaim.android.engine.FloatPoint
import com.poolaim.android.engine.GhostBallSolver
import com.poolaim.android.engine.ShotSolution

data class ProcessedPoolFrame(
    val scene: PoolScene,
    val solutions: List<ShotSolution>,
    val diagnostic: String,
    val cueStickAxis: FloatPoint? = null
)

class PoolAimProcessor(
    private val detector: PoolScreenDetector = PoolScreenDetector(),
    private val solver: GhostBallSolver = GhostBallSolver(),
    private val cueStickDetector: CueStickAxisDetector = CueStickAxisDetector()
) {
    fun process(frame: CapturedRgbaFrame): ProcessedPoolFrame {
        val scene = detector.detect(frame)
        if (scene.table == null) return ProcessedPoolFrame(scene, emptyList(), scene.diagnostic)
        val cueCandidates = scene.balls.filter {
            it.ballId == BallIdentity.CUE && it.classificationConfidence >= 0.65f
        }
        if (cueCandidates.isEmpty()) {
            return ProcessedPoolFrame(scene, emptyList(), "${scene.diagnostic}; cue ball not confidently identified")
        }
        val selectedCue = cueCandidates.maxByOrNull { it.classificationConfidence }!!
        val cleaned = if (cueCandidates.size > 1) {
            scene.copy(balls = scene.balls.filter {
                it.ballId != BallIdentity.CUE || it === selectedCue
            })
        } else scene
        val axis = cueStickDetector.detect(frame, selectedCue.center, selectedCue.radius, scene.table)
        val solutions = solver.solve(cleaned)
        val diagnostic = when {
            solutions.isNotEmpty() -> "${cleaned.diagnostic}; ${solutions.size} collision-clear shot options"
            cleaned.balls.none { it.ballId?.let { id -> id in 1..15 } == true } ->
                "${cleaned.diagnostic}; no numbered object balls identified"
            else -> "${cleaned.diagnostic}; no collision-clear shot found"
        }
        return ProcessedPoolFrame(cleaned, solutions, diagnostic, axis)
    }
}
