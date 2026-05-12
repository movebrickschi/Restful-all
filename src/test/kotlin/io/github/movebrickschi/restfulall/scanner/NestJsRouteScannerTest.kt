package io.github.movebrickschi.restfulall.scanner

import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NestJsRouteScannerTest {

    @Test
    fun `supportedExtensions returns ts and tsx`() {
        val exts = NestJsRouteScanner().supportedExtensions()
        assertEquals(setOf("ts", "tsx"), exts)
    }

    @Test
    fun `returns empty list when file has no Controller annotation`() {
        val file = TestVirtualFile(
            "plain.ts",
            """
            export class UserService {
                getUser(id: string) {
                    return { id };
                }
            }
            """.trimIndent(),
        )

        val routes = NestJsRouteScanner().scanFile(file)

        assertTrue(routes.isEmpty())
    }

    @Test
    fun `joins controller prefix and method path`() {
        val file = TestVirtualFile(
            "users.controller.ts",
            """
            import { Controller, Get } from '@nestjs/common';

            @Controller('users')
            export class UsersController {
                @Get(':id')
                async findOne(id: string) {
                    return { id };
                }
            }
            """.trimIndent(),
        )

        val route = NestJsRouteScanner().scanFile(file).single()

        assertEquals(HttpMethod.GET, route.method)
        assertEquals("/users/:id", route.fullPath)
        assertEquals("UsersController", route.className)
        assertEquals("findOne", route.functionName)
        assertEquals(Framework.NESTJS, route.framework)
    }

    @Test
    fun `controller without prefix uses method path directly`() {
        val file = TestVirtualFile(
            "auth.controller.ts",
            """
            import { Controller, Post } from '@nestjs/common';

            @Controller()
            export class AuthController {
                @Post('/login')
                login() {
                    return {};
                }
            }
            """.trimIndent(),
        )

        val route = NestJsRouteScanner().scanFile(file).single()

        assertEquals(HttpMethod.POST, route.method)
        assertEquals("/login", route.fullPath)
        assertEquals("AuthController", route.className)
    }

    @Test
    fun `method decorator without argument uses controller prefix only`() {
        val file = TestVirtualFile(
            "items.controller.ts",
            """
            import { Controller, Get } from '@nestjs/common';

            @Controller('items')
            export class ItemsController {
                @Get()
                list() {
                    return [];
                }
            }
            """.trimIndent(),
        )

        val route = NestJsRouteScanner().scanFile(file).single()

        assertEquals(HttpMethod.GET, route.method)
        assertEquals("/items", route.fullPath)
    }

    @Test
    fun `detects all supported HTTP method decorators`() {
        val file = TestVirtualFile(
            "all-methods.controller.ts",
            """
            import { Controller, Get, Post, Put, Delete, Patch, Head, Options, All } from '@nestjs/common';

            @Controller('crud')
            export class CrudController {
                @Get('/list')
                list() {}

                @Post('/create')
                create() {}

                @Put('/update')
                update() {}

                @Delete('/remove')
                remove() {}

                @Patch('/touch')
                touch() {}

                @Head('/head')
                head() {}

                @Options('/opt')
                opt() {}

                @All('/any')
                any() {}
            }
            """.trimIndent(),
        )

        val routes = NestJsRouteScanner().scanFile(file)
        val methodsFound = routes.map { it.method }.toSet()

        assertEquals(
            setOf(
                HttpMethod.GET,
                HttpMethod.POST,
                HttpMethod.PUT,
                HttpMethod.DELETE,
                HttpMethod.PATCH,
                HttpMethod.HEAD,
                HttpMethod.OPTIONS,
                HttpMethod.ALL,
            ),
            methodsFound,
        )
        assertEquals(8, routes.size)
        assertTrue(routes.all { it.fullPath.startsWith("/crud/") })
    }

    @Test
    fun `ignores method decorators inside line comments`() {
        val file = TestVirtualFile(
            "commented.controller.ts",
            """
            import { Controller, Get } from '@nestjs/common';

            @Controller('hidden')
            export class HiddenController {
                // @Get('/disabled')
                // disabled() {}

                @Get('/active')
                active() {}
            }
            """.trimIndent(),
        )

        val routes = NestJsRouteScanner().scanFile(file)

        assertEquals(1, routes.size)
        assertEquals("/hidden/active", routes.single().fullPath)
    }

    @Test
    fun `ignores method decorators inside block comments`() {
        val file = TestVirtualFile(
            "block-commented.controller.ts",
            """
            import { Controller, Post } from '@nestjs/common';

            @Controller('demo')
            export class DemoController {
                /*
                 * @Post('/dead')
                 * dead() {}
                 */

                @Post('/alive')
                alive() {}
            }
            """.trimIndent(),
        )

        val routes = NestJsRouteScanner().scanFile(file)

        assertEquals(1, routes.size)
        assertEquals(HttpMethod.POST, routes.single().method)
        assertEquals("/demo/alive", routes.single().fullPath)
    }

    @Test
    fun `does not collect method decorators before controller`() {
        val file = TestVirtualFile(
            "no-controller.ts",
            """
            import { Get } from '@nestjs/common';

            @Get('/orphan')
            function orphan() {}
            """.trimIndent(),
        )

        val routes = NestJsRouteScanner().scanFile(file)

        assertTrue(routes.isEmpty())
    }

    @Test
    fun `records lineNumber of method decorator`() {
        val file = TestVirtualFile(
            "line-number.controller.ts",
            """
            import { Controller, Get } from '@nestjs/common';

            @Controller('lines')
            export class LinesController {
                @Get('/x')
                x() {}
            }
            """.trimIndent(),
        )

        val route = NestJsRouteScanner().scanFile(file).single()

        // @Get('/x') is on the 5th line (index 4).
        assertEquals(4, route.lineNumber)
    }

    @Test
    fun `recognises bare Controller without parentheses`() {
        val file = TestVirtualFile(
            "bare.controller.ts",
            """
            import { Controller, Get } from '@nestjs/common';

            @Controller
            export class BareController {
                @Get('/bare')
                bare() {
                    return {};
                }
            }
            """.trimIndent(),
        )

        val route = NestJsRouteScanner().scanFile(file).single()

        assertEquals(HttpMethod.GET, route.method)
        assertEquals("/bare", route.fullPath)
        assertEquals("BareController", route.className)
    }

    @Test
    fun `falls back to unknown when no class declaration follows`() {
        val file = TestVirtualFile(
            "dangling.controller.ts",
            """
            @Controller('orphan')
            @Get('/x')
            export const handler = () => {};
            """.trimIndent(),
        )

        val route = NestJsRouteScanner().scanFile(file).single()

        assertEquals("Unknown", route.className)
    }
}
