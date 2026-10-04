package com.multisuperplayer.feature.library.di

import com.multisuperplayer.feature.library.BrowseViewModel
import com.multisuperplayer.feature.library.LibraryViewModel
import com.multisuperplayer.feature.library.NetworkViewModel
import com.multisuperplayer.feature.library.PlaylistsViewModel
import com.multisuperplayer.feature.library.RecentViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * 媒体库功能域的依赖绑定。
 *
 * ViewModel 由**功能模块自己**注册，而不是集中写在 app 里。集中注册的代价是：
 * 删掉 `:feature:library` 之后，app 里会留下一堆指向不存在类的引用，
 * 编译失败的位置离真正的原因很远。
 */
val libraryModule = module {
    viewModelOf(::LibraryViewModel)
    // 「最近播放」与「播放列表」各自开一个 VM，而不是塞进 LibraryViewModel：
    // 那两个页面是独立的目的地，要独立的作用域与生命周期；
    // 共用一个 VM 的话，从媒体库切到最近播放会顺手把媒体库的选中态也带过去。
    viewModelOf(::RecentViewModel)
    viewModelOf(::PlaylistsViewModel)
    viewModelOf(::BrowseViewModel)
    // 网络地址页。它的入口在浏览页的来源清单里（不是底部标签页），
    // 但状态归它自己：地址框里的半截输入和浏览页的目录位置没有任何关系。
    viewModelOf(::NetworkViewModel)
}
