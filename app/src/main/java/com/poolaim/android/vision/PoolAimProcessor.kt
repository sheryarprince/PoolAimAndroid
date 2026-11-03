package com.poolaim.android.vision

import com.poolaim.android.engine.CalibratedTableTransform
import com.poolaim.android.engine.GhostBallSolver
import com.poolaim.android.engine.PoolAimSolution
import com.poolaim.android.engine.PoolBallState

data class ProcessedPoolFrame(
    val scene: PoolScene,
    val solutions: List<PoolAimSolution>,
    val diagnostic: String
)

/** Joins frame recognition, screen-to-table calibration and the ghost-ball solver. */
class PoolAimProcessor(
    private val detector: PoolScreenDetector = PoolScreenDetector(),
    private val solver: GhostBallSolver = GhostBallSolver()
) {
    fun process(frame: com.poolaim.android.capture.CapturedRgbaFrame): ProcessedPoolFrame {
        val scene = detector.detect(frame)
        val bounds = scene.table ?: return ProcessedPoolFrame(scene, emptyList(), scene.diagnostic)
        if (scene.balls.size < 2) return ProcessedPoolFrame(scene, emptyList(),
            "Table found; fewer than two ball candidates")
        // Current detector cannot yet reliably distinguish the cue ball from white highlights.
        // Require exactly one very bright, near-white candidate before solving.
        val transform = CalibratedTableTransform(bounds)
        val cueCandidates = scene.balls.filter { it.isCueCandidate }
        if (cueCandidates.size != 1) return ProcessedPoolFrame(scene, emptyList(),
            "Found ${scene.balls.size} candidates; cue-ball confidence ambiguous")
        val cuePoint = transform.toTable(cueCandidates.single().center)
        val cue = PoolBallState(0, cuePoint.x.toDouble(), cuePoint.y.toDouble())
        val objects = scene.balls.filterNot { it.isCueCandidate }.mapIndexed { index, ball ->
            val p = transform.toTable(ball.center)
            PoolBallState(index + 1, p.x.toDouble(), p.y.toDouble())
        }
        val solutions = solver.solve(cue, objects)
        return ProcessedPoolFrame(scene, solutions,
            if (solutions.isEmpty()) "Scene recognized; no geometric pot candidate" else
                "Scene recognized; ${solutions.size} geometric aim candidates")
    }
}
