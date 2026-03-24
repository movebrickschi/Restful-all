package io.github.movebrickschi.restfulall.scanner

import com.intellij.openapi.vfs.VirtualFile
import io.github.movebrickschi.restfulall.model.RouteInfo

interface RouteScanner {
    fun supportedExtensions(): Set<String>
    fun scanFile(file: VirtualFile): List<RouteInfo>
}
