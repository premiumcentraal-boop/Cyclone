package com.cyclone.mobile.automation.stock.instagram

import android.content.Context
import com.cyclone.mobile.automation.SkillDefinition
import com.cyclone.mobile.automation.StockSkillGateway
import com.cyclone.mobile.automation.StockSkillRequest
import com.cyclone.mobile.automation.StockSkillResult

/** Single Mobile stock-skill registry so adding a new built-in never creates another automation engine. */
class InstagramStockSkillCollectionGateway(private val context: Context) : StockSkillGateway {
    override fun execute(request: StockSkillRequest): StockSkillResult = when (request.skillId) {
        InstagramStockSkillIds.REELS_WARMUP -> InstagramReelsWarmupRunner(context).run(request)
        InstagramReelsFollowingStockSkill.ID -> InstagramReelsFollowingRunner(context).run(request)
        InstagramColdDmsStockSkill.ID -> InstagramColdDmsRunner(context).run(request)
        InstagramPreparePostStockSkill.ID -> InstagramPreparePostRunner(context).run(request)
        else -> StockSkillResult(false, message = "unknown_stock_skill:${request.skillId}")
    }

    companion object {
        val definitions: List<SkillDefinition> = listOf(
            InstagramReelsWarmupStockSkill.definition,
            InstagramReelsFollowingStockSkill.definition,
            InstagramColdDmsStockSkill.definition,
            InstagramPreparePostStockSkill.definition,
        )
    }
}
