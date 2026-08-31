package com.github.wekite.features.items.chat

import com.github.wekite.dexkit.abc.IResolveDex
import com.github.wekite.dexkit.dsl.dexMethod
import com.github.wekite.features.core.Feature
import com.github.wekite.features.core.SwitchFeature
import com.github.wekite.utils.WeLogger
import dev.ujhhgtg.reflekt.firstField
import dev.ujhhgtg.reflekt.reflekt
import org.luckypray.dexkit.DexKitBridge

/**
 * 清除自定义聊天壁纸下微信自动添加的"可读性"浅灰背景:
 *
 * 1. 时间标签/系统提示/拍一拍/参与接龙/防撤回提示 的灰色胶囊背景 — 全部来自
 *    com.tencent.mm.ui.chatting.component.v2 的零参 int 方法这一个资源出口
 *    (8.0.77 为 l0(), 旧版本为 m0(); chatBgAttr.d=true 时返回两个 drawable id)。
 *    hook 恒返回 0 后, 所有调用方都变成 setBackgroundResource(0) = 无背景,
 *    与微信"未开背景"时的行为完全一致, 不存在时序竞态。
 *
 *    防撤回提示条 (AntiMessageRecall) 是通过 createSimpleMsgInfoAndInsert 插入一条
 *    系统类型消息渲染的, 走的就是系统提示胶囊的同一出口, 因此一并透明化。
 *
 * 2. 引用消息的灰色胶囊背景 — 引用数据构造器 (8.0.77: yj5.f.a, 旧版本 sg5.f.a)
 *    把 drawable id 硬编码进数据持有类 (8.0.77: hi5/b.i, 旧版本 df5/b.i) 的
 *    i 字段。hook 后置空该字段, 引用渲染就不会设置灰底。
 */
@Feature(name = "壁纸元素透明化", categories = ["聊天"], description = "设置自定义聊天背景后，去除时间胶囊、拍一拍提示、引用消息、系统通知（含防撤回提示）的灰色背景，让壁纸直接显示")
object WallpaperTransparentItems : SwitchFeature(), IResolveDex {

    private const val TAG = "WallpaperTransparentItems"

    /**
     * com.tencent.mm.ui.chatting.component.v2 的零参 int 方法 (8.0.77: l0, 旧: m0)
     * — 时间胶囊背景资源唯一出口。
     */
    private val methodTimePillRes by dexMethod()

    /**
     * 引用数据构造器 (8.0.77: yj5.f.a, 旧版本: sg5.f.a), 均为 6 参数 void:
     * — 第 5 个参数 (index 4) 是数据持有类 (hi5/b / df5/b), 其中 i 字段为背景资源。
     */
    private val methodQuoteDataBuilder by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        // 8.0.77+: component.v2#l0(); 旧版本回退: component.v2#m0()
        if (!methodTimePillRes.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass = "com.tencent.mm.ui.chatting.component.v2"
                name = "l0"
                paramCount = 0
                returnType = "int"
            }
        }) {
            methodTimePillRes.find(dexKit, allowFailure = true) {
                matcher {
                    declaredClass = "com.tencent.mm.ui.chatting.component.v2"
                    name = "m0"
                    paramCount = 0
                    returnType = "int"
                }
            }
        }
        // 8.0.77+: yj5.f.a(...); 旧版本回退: sg5.f.a(...)
        if (!methodQuoteDataBuilder.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass = "yj5.f"
                name = "a"
                paramCount = 6
                returnType = "void"
            }
        }) {
            methodQuoteDataBuilder.find(dexKit, allowFailure = true) {
                matcher {
                    declaredClass = "sg5.f"
                    name = "a"
                    paramCount = 6
                    returnType = "void"
                }
            }
        }
    }

    override fun onEnable() {
        if (!methodTimePillRes.isPlaceholder) {
            methodTimePillRes.hookBefore {
                // 恒返回 0: 所有调用方 setBackgroundResource(0) 即无背景
                result = 0
            }
            WeLogger.i(TAG, "time pill background hook enabled")
        } else {
            WeLogger.e(TAG, "methodTimePillRes unresolved, time pill background not cleared")
        }

        if (!methodQuoteDataBuilder.isPlaceholder) {
            methodQuoteDataBuilder.hookAfter {
                val quoteData = args.getOrNull(4) ?: return@hookAfter
                runCatching {
                    quoteData.reflekt()
                        .firstField { name = "i" }
                        .set(null)
                }.onFailure { WeLogger.e(TAG, "failed to clear quote background field", it) }
            }
            WeLogger.i(TAG, "quote background hook enabled")
        } else {
            WeLogger.e(TAG, "methodQuoteDataBuilder unresolved, quote background not cleared")
        }
    }
}
