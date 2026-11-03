package com.poolaim.android.vision

import com.poolaim.android.capture.CapturedRgbaFrame
import com.poolaim.android.engine.GhostBallSolver
import com.poolaim.android.engine.ShotSolution

data class ProcessedPoolFrame(
    val scene: PoolScene,
    val solutions: List<ShotSolution>,
    val diagnostic: String,
    val gameAimGuides: List<GameAimGuideSegment> = emptyList()
)

class PoolAimProcessor(
    private val detector: PoolScreenDetector = PoolScreenDetector(),
    private val solver: GhostBallSolver = GhostBallSolver(),
    private val gameAimGuideDetector: GameAimGuideDetector = GameAimGuideDetector()
) {
    fun process(
        frame: CapturedRgbaFrame,
        excludedAreas: List<TableBounds> = emptyList()
    ): ProcessedPoolFrame {
        val scene = detector.detect(frame)
        val table = scene.table
            ?: return ProcessedPoolFrame(scene, emptyList(), scene.diagnostic)

        // Guide detection is independent of ball classification. The game's
        // white guide is detected directly from its visible line pixels.
        val guides = gameAimGuideDetector.detect(frame, table, excludedAreas, scene.balls)
        val solutions = solver.solve(scene)
        val diagnostic = when {
            guides.isNotEmpty() ->
                "${scene.diagnostic}; ${guides.size} in-game white guide stroke(s) detected"
            else ->
                "${scene.diagnostic}; no long white in-game guide stroke detected"
        }
        return ProcessedPoolFrame(scene, solutions, diagnostic, guides)
    }
}
