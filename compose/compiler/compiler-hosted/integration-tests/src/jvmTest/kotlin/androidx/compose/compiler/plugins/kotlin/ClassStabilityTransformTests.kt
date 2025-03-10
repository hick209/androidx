/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.compiler.plugins.kotlin

import androidx.compose.compiler.plugins.kotlin.analysis.FqNameMatcher
import androidx.compose.compiler.plugins.kotlin.analysis.StabilityInferencer
import androidx.compose.compiler.plugins.kotlin.facade.SourceFile
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.statements
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassStabilityTransformTests(useFir: Boolean) : AbstractIrTransformTest(useFir) {
    @Test
    fun testEmptyClassIsStable() = assertStability(
        "class Foo",
        "Stable"
    )

    @Test
    fun testSingleValPrimitivePropIsStable() = assertStability(
        "class Foo(val value: Int)",
        "Stable"
    )

    @Test
    fun testSingleVarPrimitivePropIsUnstable() = assertStability(
        "class Foo(var value: Int)",
        "Unstable"
    )

    @Test
    fun testSingleValTypeParamIsStableIfParamIs() = assertStability(
        "class Foo<T>(val value: T)",
        "Parameter(T)"
    )

    @Test
    fun testSingleVarTypeParamIsUnstable() = assertStability(
        "class Foo<T>(var value: T)",
        "Unstable"
    )

    @Test
    fun testNonCtorVarIsUnstable() = assertStability(
        "class Foo(value: Int) { var value: Int = value }",
        "Unstable"
    )

    @Test
    fun testNonCtorValIsStable() = assertStability(
        "class Foo(value: Int) { val value: Int = value }",
        "Stable"
    )

    @Test
    fun testMutableStateDelegateVarIsStable() = assertStability(
        "class Foo(value: Int) { var value by mutableStateOf(value) }",
        "Stable"
    )

    @Test
    fun testLazyValIsUnstable() = assertStability(
        "class Foo(value: Int) { val square by lazy { value * value } }",
        "Unstable"
    )

    @Test
    fun testNonFieldCtorParamDoesNotImpactStability() = assertStability(
        "class Foo<T>(val a: Int, b: T) { val c: Int = b.hashCode() }",
        "Stable"
    )

    @Test
    fun testNonBackingFieldUnstableVarIsStable() = assertStability(
        """
            class Foo {
                var p1: Unstable
                    get() { TODO() }
                    set(value) { }
            }
        """,
        "Stable"
    )

    @Test
    fun testNonBackingFieldUnstableValIsStable() = assertStability(
        """
            class Foo {
                val p1: Unstable
                    get() { return Unstable() }
            }
        """,
        "Stable"
    )

    @Test
    fun testTypeParameterWithNonExactBackingFieldType() = assertStability(
        "class Foo<T>(val a: List<T>)",
        "Unstable"
    )

    @Test
    fun testTypeParamNonExactCtorParamExactBackingFields() = assertStability(
        "class Foo<T>(a: List<T>) { val b = a.first(); val c = a.last() }",
        "Parameter(T),Parameter(T)"
    )

    @Test
    fun testInterfacesAreUncertain() = assertStability(
        "interface Foo",
        "Uncertain(Foo)"
    )

    @Test
    fun testInterfaceWithStableValAreUncertain() = assertStability(
        "interface Foo { val value: Int }",
        "Uncertain(Foo)"
    )

    @Test
    fun testCrossModuleTypesResultInRuntimeStability() = assertStability(
        """
            class A
            class B
            class C
        """,
        "class D(val a: A, val v: B, val C: C)",
        "Runtime(A),Runtime(B),Runtime(C)"
    )

    @Test
    fun testStable17() = assertStability(
        """
            class Counter {
                private var count: Int = 0
                fun getCount(): Int = count
                fun increment() { count++ }
            }
        """,
        "Unstable"
    )

    @Test
    fun testValueClassIsStableIfItsValueIsStable() = assertStability(
        """
            @JvmInline value class Px(val pixels: Int)
        """,
        "Stable"
    )

    @Test
    fun testValueClassIsUnstableIfItsValueIsUnstable() = assertStability(
        """
            @JvmInline value class UnstableWrapper(val backingValue: Unstable)
        """,
        "Unstable"
    )

    @Test
    fun testValueClassIsStableIfAnnotatedAsStableRegardlessOfWrappedValue() = assertStability(
        """
            @Stable @JvmInline value class StableWrapper(val backingValue: Unstable)
        """,
        "Stable"
    )

    @Test
    fun testGenericValueClassIsStableIfTypeIsStable() = assertStability(
        """
            @JvmInline value class PairWrapper<T, U>(val pair: Pair<T, U>)
        """,
        "Parameter(T),Parameter(U)"
    )

    @Test
    fun testDeeplyNestedValueClassIsTreatedAsStable() = assertStability(
        """
            @Stable @JvmInline value class UnsafeStableList(val list: MutableList<Int>)

            @JvmInline value class StableWrapper(val backingValue: UnsafeStableList)
        """,
        """
            @JvmInline value class InferredStable(val backingValue: StableWrapper)
        """,
        "Stable"
    )

    @Test
    fun testProtobufLiteTypesAreStable() = assertStability(
        """
            class Foo(val x: androidx.compose.compiler.plugins.StabilityTestProtos.SampleProto)
        """,
        "Stable"
    )

    @Test
    fun testPairIsStableIfItsTypesAre() = assertStability(
        """
            class Foo<T, V>(val x: Pair<T, V>)
        """,
        "Parameter(T),Parameter(V)"
    )

    @Test
    fun testPairOfCrossModuleTypesAreRuntimeStable() = assertStability(
        """
            class A
            class B
        """,
        "class Foo(val x: Pair<A, B>)",
        "Runtime(A),Runtime(B)"
    )

    @Test
    fun testGuavaImmutableListIsStableIfItsTypesAre() = assertStability(
        """
            class Foo<T>(val x: com.google.common.collect.ImmutableList<T>)
        """,
        "Parameter(T)"
    )

    @Test
    fun testGuavaImmutableListCrossModuleTypesAreRuntimeStable() = assertStability(
        """
            class A
        """,
        """
            class Foo(val x: com.google.common.collect.ImmutableList<A>)
        """,
        "Runtime(A)"
    )

    @Test
    fun testGuavaImmutableSetIsStableIfItsTypesAre() = assertStability(
        """
            class Foo<T>(val x: com.google.common.collect.ImmutableSet<T>)
        """,
        "Parameter(T)"
    )

    @Test
    fun testGuavaImmutableSetCrossModuleTypesAreRuntimeStable() = assertStability(
        """
            class A
        """,
        """
            class Foo(val x: com.google.common.collect.ImmutableSet<A>)
        """,
        "Runtime(A)"
    )

    @Test
    fun testGuavaImmutableMapIsStableIfItsTypesAre() = assertStability(
        """
            class Foo<K, V>(val x: com.google.common.collect.ImmutableMap<K, V>)
        """,
        "Parameter(K),Parameter(V)"
    )

    @Test
    fun testGuavaImmutableMapCrossModuleTypesAreRuntimeStable() = assertStability(
        """
            class A
            class B
        """,
        """
            class Foo(val x: com.google.common.collect.ImmutableMap<A, B>)
        """,
        "Runtime(A),Runtime(B)"
    )

    @Test
    fun testKotlinxImmutableCollectionIsStableIfItsTypesAre() = assertStability(
        """
            class Foo<T>(val x: kotlinx.collections.immutable.ImmutableCollection<T>)
        """,
        "Parameter(T)"
    )

    @Test
    fun testKotlinxImmutableCollectionCrossModuleTypesAreRuntimeStable() = assertStability(
        """
            class A
        """,
        """
            class Foo(val x: kotlinx.collections.immutable.ImmutableCollection<A>)
        """,
        "Runtime(A)"
    )

    @Test
    fun testKotlinxImmutableListIsStableIfItsTypesAre() = assertStability(
        """
            class Foo<T>(val x: kotlinx.collections.immutable.ImmutableList<T>)
        """,
        "Parameter(T)"
    )

    @Test
    fun testKotlinxImmutableListCrossModuleTypesAreRuntimeStable() = assertStability(
        """
            class A
        """,
        """
            class Foo(val x: kotlinx.collections.immutable.ImmutableList<A>)
        """,
        "Runtime(A)"
    )

    @Test
    fun testKotlinxImmutableSetIsStableIfItsTypesAre() = assertStability(
        """
            class Foo<T>(val x: kotlinx.collections.immutable.ImmutableSet<T>)
        """,
        "Parameter(T)"
    )

    @Test
    fun testKotlinxImmutableSetCrossModuleTypesAreRuntimeStable() = assertStability(
        """
            class A
        """,
        """
            class Foo(val x: kotlinx.collections.immutable.ImmutableSet<A>)
        """,
        "Runtime(A)"
    )

    @Test
    fun testKotlinxImmutableMapIsStableIfItsTypesAre() = assertStability(
        """
            class Foo<K, V>(val x: kotlinx.collections.immutable.ImmutableMap<K, V>)
        """,
        "Parameter(K),Parameter(V)"
    )

    @Test
    fun testKotlinxImmutableMapCrossModuleTypesAreRuntimeStable() = assertStability(
        """
            class A
            class B
        """,
        """
            class Foo(val x: kotlinx.collections.immutable.ImmutableMap<A, B>)
        """,
        "Runtime(A),Runtime(B)"
    )

    @Test
    fun testKotlinxPersistentCollectionIsStableIfItsTypesAre() = assertStability(
        """
            class Foo<T>(val x: kotlinx.collections.immutable.PersistentCollection<T>)
        """,
        "Parameter(T)"
    )

    @Test
    fun testKotlinxPersistentCollectionCrossModuleTypesAreRuntimeStable() = assertStability(
        """
            class A
        """,
        """
            class Foo(val x: kotlinx.collections.immutable.PersistentCollection<A>)
        """,
        "Runtime(A)"
    )

    @Test
    fun testKotlinxPersistentListIsStableIfItsTypesAre() = assertStability(
        """
            class Foo<T>(val x: kotlinx.collections.immutable.PersistentList<T>)
        """,
        "Parameter(T)"
    )

    @Test
    fun testKotlinxPersistentListCrossModuleTypesAreRuntimeStable() = assertStability(
        """
            class A
        """,
        """
            class Foo(val x: kotlinx.collections.immutable.PersistentList<A>)
        """,
        "Runtime(A)"
    )

    @Test
    fun testKotlinxPersistentSetIsStableIfItsTypesAre() = assertStability(
        """
            class Foo<T>(val x: kotlinx.collections.immutable.PersistentSet<T>)
        """,
        "Parameter(T)"
    )

    @Test
    fun testKotlinxPersistentSetCrossModuleTypesAreRuntimeStable() = assertStability(
        """
            class A
        """,
        """
            class Foo(val x: kotlinx.collections.immutable.PersistentSet<A>)
        """,
        "Runtime(A)"
    )

    @Test
    fun testKotlinxPersistentMapIsStableIfItsTypesAre() = assertStability(
        """
            class Foo<K, V>(val x: kotlinx.collections.immutable.PersistentMap<K, V>)""",
        "Parameter(K),Parameter(V)"
    )

    @Test
    fun testKotlinxPersistentMapCrossModuleTypesAreRuntimeStable() = assertStability(
        """
            class A
            class B
        """,
        """
            class Foo(val x: kotlinx.collections.immutable.PersistentMap<A, B>)""",
        "Runtime(A),Runtime(B)"
    )

    @Test
    fun testDaggerLazyIsStableIfItsTypeIs() = assertStability(
        """
            class Foo<T>(val x: dagger.Lazy<T>)
        """,
        "Parameter(T)"
    )

    @Test
    fun testDaggerLazyOfCrossModuleTypeIsRuntimeStable() = assertStability(
        """
            class A
        """,
        "class Foo(val x: dagger.Lazy<A>)",
        "Runtime(A)"
    )

    @Test
    fun testVarPropDelegateWithCrossModuleStableDelegateTypeIsStable() = assertStability(
        """
            @Stable
            class StableDelegate {
                operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
                }
                operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                    return 10
                }
            }
        """,
        "class Foo { var p1 by StableDelegate() }",
        "Stable"
    )

    @Test
    fun testVarPropDelegateWithStableDelegateTypeIsStable() = assertStability(
        """
        @Stable
        class StableDelegate {
            operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
            }
            operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                return 10
            }
        }
        class Foo { var p1 by StableDelegate() }
        """,
        "Stable"
    )

    @Test
    fun testVarPropDelegateOfInferredStableDelegate() = assertStability(
        """
        class StableDelegate {
            operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
            }
            operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                return 10
            }
        }
        class Foo { var p1 by StableDelegate() }
        """,
        "Stable"
    )

    @Test
    fun testVarPropDelegateOfCrossModuleInferredStableDelegate() = assertStability(
        """
            class StableDelegate {
                operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
                }
                operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                    return 10
                }
            }
        """,
        "class Foo { var p1 by StableDelegate() }",
        "Runtime(StableDelegate)"
    )

    @Test
    fun testStableDelegateWithTypeParamButNoBackingFieldDoesntDependOnReturnType() =
        assertStability(
            """
                class StableDelegate<T> {
                    operator fun setValue(thisObj: Any?, property: KProperty<*>, value: T) {
                    }
                    operator fun getValue(thisObj: Any?, property: KProperty<*>): T {
                        error("")
                    }
                }
                class Bar
            """,
            "class Foo { var p1 by StableDelegate<Bar>() }",
            "Runtime(StableDelegate)"
        )

    @Test
    fun testStable26() = assertStability(
        """
            class A
            class B
            class C
        """,
        """
            class Foo(a: A, b: B, c: C) {
                var a by mutableStateOf(a)
                var b by mutableStateOf(b)
                var c by mutableStateOf(c)
            }
        """,
        "Stable"
    )

    @Test
    fun testExplicitlyMarkedStableTypesAreStable() = assertStability(
        """
            @Stable
            class A
        """,
        """
            class Foo(val a: A)
        """,
        "Stable"
    )

    @Test
    fun testExternalStableTypesFieldsAreStable() = assertStability(
        externalSrc = """
            class A 
        """,
        classDefSrc = """
            class Foo(val a: A)
        """,
        stability = "Stable",
        externalTypes = setOf("dependency.A")
    )

    @Test
    fun testClassesExtendingExternalStableInterfacesAreStable() = assertStability(
        externalSrc = """
            interface A 
        """,
        classDefSrc = """
            class Foo : A
        """,
        stability = "Stable",
        externalTypes = setOf("dependency.A")
    )

    @Test
    fun testExternalWildcardTypesAreStable() = assertStability(
        externalSrc = """
            class A
        """,
        classDefSrc = """
            class Foo(val a: A)
        """,
        stability = "Stable",
        externalTypes = setOf("dependency.*")
    )

    @Test
    fun testExternalOnlySingleWildcardTypesAreStable() = assertStability(
        externalSrc = """
            class A

            class B {
                class C
            }
        """,
        classDefSrc = """
            class Foo(val a: A, val b: B, val c: B.C)
        """,
        stability = "Runtime(B)",
        externalTypes = setOf("dependency.A", "dependency.B.*")
    )

    @Test
    fun testExternalDoubleWildcardTypesAreStable() = assertStability(
        externalSrc = """
            class A {
                class B {
                    class C
                }
            }
        """,
        classDefSrc = """
            class Foo(val a: A, val b: A.B, val c: A.B.C)
        """,
        stability = "Stable",
        externalTypes = setOf("dependency.**")
    )

    @Test
    fun testExternalDoubleWildcardInMiddleTypesAreStable() = assertStability(
        externalSrc = """
            class A {
                class B {
                    class C
                }
            }
        """,
        classDefSrc = """
            class Foo(val a: A, val b: A.B, val c: A.B.C)
        """,
        stability = "Runtime(A),Runtime(B)",
        externalTypes = setOf("dependency.**.C")
    )

    @Test
    fun testExternalDoubleWildcardWithPrefixInMiddleTypesAreStable() = assertStability(
        externalSrc = """
            class A {
                class Ba {
                    class C
                }
                class Bb {
                    class C
                }
            }
        """,
        classDefSrc = """
            class Foo(val a: A, val ba: A.Ba, val bb: A.Bb, val ca: A.Ba.C, val cb: A.Bb.C)
        """,
        stability = "Runtime(A)",
        externalTypes = setOf("dependency.A.B**")
    )

    @Test
    fun testExternalMixedWildcardsTypesAreStable() = assertStability(
        externalSrc = """
            class A {
                class B {
                    class C
                }
            }
        """,
        classDefSrc = """
            class Foo(val a: A, val b: A.B, val c: A.B.C)
        """,
        stability = "Runtime(A)",
        externalTypes = setOf("dependency.**.*")
    )

    @Test
    fun testExternalMultiWildcardFirstTypesAreStable() = assertStability(
        externalSrc = """
            class A {
                class B {
                    class C
                }
            }
        """,
        classDefSrc = """
            class Foo(val a: A, val b: A.B, val c: A.B.C)
        """,
        stability = "Stable",
        externalTypes = setOf("**")
    )

    @Test
    fun testExternalWildcardFirstTypesAreStable() = assertStability(
        externalSrc = """
            class A
        """,
        classDefSrc = """
            class Foo(val a: A)
        """,
        stability = "Stable",
        externalTypes = setOf("*.A")
    )

    @Test
    fun testExternalMultipleSingleWildcardsTypesAreStable() = assertStability(
        externalSrc = """
            class A {
                class B {
                    class C
                }
                class D {
                    class E
                }
            }
        """,
        classDefSrc = """
            class Foo(val c: A.B.C, val e: A.D.E)
        """,
        stability = "Stable",
        externalTypes = setOf("dependency.*.B.*", "dependency.A.D.E")
    )

    @Test
    fun testExternalGenericTypesAreParameterDependent() = assertStability(
        externalSrc = """
            class Foo<T>(val x: T)
        """,
        classDefSrc = """
            class Test<T>(val foo: Foo<T>)
        """,
        stability = "Parameter(T)",
        externalTypes = setOf("dependency.Foo")
    )

    @Test
    fun testExternalGenericTypesAreCanIgnoreParameters() = assertStability(
        externalSrc = """
            class Foo<X, Y>(val x: X, val y: Y)
        """,
        classDefSrc = """
            class Test<X, Y>(val foo: Foo<X, Y>)
        """,
        stability = "Parameter(X)",
        externalTypes = setOf("dependency.Foo<*,_>")
    )

    @Test
    fun testExternalGenericTypesAreCanBeRuntimeStable() = assertStability(
        externalSrc = """
            class A
            class B
            class Foo<X, Y>(val x: X, val y: Y)
        """,
        classDefSrc = """
            class Test(val foo: Foo<A, B>)
        """,
        stability = "Runtime(B)",
        externalTypes = setOf("dependency.Foo<_,*>")
    )

    @Test
    fun testExternalGenericDefinedTypesAreStable() = assertStability(
        externalSrc = """
            class A
            class Foo<T>(val x: T)
        """,
        classDefSrc = """
            class Test(val foo: Foo<A>)
        """,
        stability = "Stable",
        externalTypes = setOf("dependency.Foo", "dependency.A")
    )

    @Test
    fun testExternalDeepPackageNameIsStable() = assertStability(
        externalSrc = """
            class A
        """,
        classDefSrc = """
            class Test(val foo: A)
        """,
        stability = "Stable",
        externalTypes = setOf("dependency.b.c.d.A"),
        packageName = "dependency.b.c.d"
    )

    @Test
    fun testListOfCallWithPrimitiveTypeIsStable() = assertStability(
        "",
        "",
        "listOf(1)",
        "Stable"
    )

    @Test
    fun testListOfCallWithLocalInferredStableTypeIsStable() = assertStability(
        "",
        "class Foo",
        "listOf(Foo())",
        "Stable"
    )

    @Test
    fun testListOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStability(
        "class Foo",
        "",
        "listOf(Foo())",
        "Runtime(Foo)"
    )

    @Test
    fun testMapOfCallWithPrimitiveTypesIsStable() = assertStability(
        "",
        "",
        "mapOf(1 to 1)",
        "Stable,Stable"
    )

    @Test
    fun testMapOfCallWithStableTypeIsStable() = assertStability(
        "",
        """
            class A
            class B
        """,
        "mapOf(A() to B())",
        "Stable,Stable"
    )

    @Test
    fun testMapOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStability(
        """
            class A
            class B
        """,
        "",
        "mapOf(A() to B())",
        "Runtime(A),Runtime(B)"
    )

    @Test
    fun testSetOfCallWithPrimitiveTypesIsStable() = assertStability(
        "",
        "",
        "setOf(1)",
        "Stable"
    )

    @Test
    fun testSetOfCallWithStableTypeIsStable() = assertStability(
        "",
        """
            class A
        """,
        "setOf(A())",
        "Stable"
    )

    @Test
    fun testSetOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStability(
        """
            class A
        """,
        "",
        "setOf(A())",
        "Runtime(A)"
    )

    @Test
    fun testImmutableListOfCallWithPrimitiveTypeIsStable() = assertStability(
        "",
        "",
        "kotlinx.collections.immutable.immutableListOf(1)",
        "Stable"
    )

    @Test
    fun testImmutableListOfCallWithLocalInferredStableTypeIsStable() = assertStability(
        "",
        "class Foo",
        "kotlinx.collections.immutable.immutableListOf(Foo())",
        "Stable"
    )

    @Test
    fun testImmutableListOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStability(
        "class Foo",
        "",
        "kotlinx.collections.immutable.immutableListOf(Foo())",
        "Runtime(Foo)"
    )

    @Test
    fun testImmutableMapOfCallWithPrimitiveTypesIsStable() = assertStability(
        "",
        "",
        "kotlinx.collections.immutable.immutableMapOf(1 to 1)",
        "Stable,Stable"
    )

    @Test
    fun testImmutableMapOfCallWithStableTypeIsStable() = assertStability(
        "",
        """
            class A
            class B
        """,
        "kotlinx.collections.immutable.immutableMapOf(A() to B())",
        "Stable,Stable"
    )

    @Test
    fun testImmutableMapOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStability(
        """
            class A
            class B
        """,
        "",
        "kotlinx.collections.immutable.immutableMapOf(A() to B())",
        "Runtime(A),Runtime(B)"
    )

    @Test
    fun testImmutableSetOfCallWithPrimitiveTypesIsStable() = assertStability(
        "",
        "",
        "kotlinx.collections.immutable.immutableSetOf(1)",
        "Stable"
    )

    @Test
    fun testImmutableSetOfCallWithStableTypeIsStable() = assertStability(
        "",
        """
            class A
        """,
        "kotlinx.collections.immutable.immutableSetOf(A())",
        "Stable"
    )

    @Test
    fun testImmutableSetOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStability(
        """
            class A
        """,
        "",
        "kotlinx.collections.immutable.immutableSetOf(A())",
        "Runtime(A)"
    )

    @Test
    fun testPersistentListOfCallWithPrimitiveTypeIsStable() = assertStability(
        "",
        "",
        "kotlinx.collections.immutable.persistentListOf(1)",
        "Stable"
    )

    @Test
    fun testPersistentListOfCallWithLocalInferredStableTypeIsStable() = assertStability(
        "",
        "class Foo",
        "kotlinx.collections.immutable.persistentListOf(Foo())",
        "Stable"
    )

    @Test
    fun testPersistentListOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStability(
        "class Foo",
        "",
        "kotlinx.collections.immutable.persistentListOf(Foo())",
        "Runtime(Foo)"
    )

    @Test
    fun testPersistentMapOfCallWithPrimitiveTypesIsStable() = assertStability(
        "",
        "",
        "kotlinx.collections.immutable.persistentMapOf(1 to 1)",
        "Stable,Stable"
    )

    @Test
    fun testPersistentMapOfCallWithStableTypeIsStable() = assertStability(
        "",
        """
            class A
            class B
        """,
        "kotlinx.collections.immutable.persistentMapOf(A() to B())",
        "Stable,Stable"
    )

    @Test
    fun testPersistentMapOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStability(
        """
            class A
            class B
        """,
        "",
        "kotlinx.collections.immutable.persistentMapOf(A() to B())",
        "Runtime(A),Runtime(B)"
    )

    @Test
    fun testPersistentSetOfCallWithPrimitiveTypesIsStable() = assertStability(
        "",
        "",
        "kotlinx.collections.immutable.persistentSetOf(1)",
        "Stable"
    )

    @Test
    fun testPersistentSetOfCallWithStableTypeIsStable() = assertStability(
        "",
        """
            class A
        """,
        "kotlinx.collections.immutable.persistentSetOf(A())",
        "Stable"
    )

    @Test
    fun testPersistentSetOfCallWithExternalInferredStableTypeIsRuntimeStable() = assertStability(
        """
            class A
        """,
        "",
        "kotlinx.collections.immutable.persistentSetOf(A())",
        "Runtime(A)"
    )

    @Test
    fun testEmptyClass() = assertTransform(
        """
            class Foo
        """
    )

    @Test
    fun testStabilityTransformOfVariousTypes() = assertTransform(
        """
            import androidx.compose.runtime.Stable
            import kotlin.reflect.KProperty

            @Stable
            class StableDelegate {
                operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
                }
                operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                    return 10
                }
            }

            class UnstableDelegate {
                var value: Int = 0
                operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
                    this.value = value
                }
                operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                    return 10
                }
            }
            class Unstable { var value: Int = 0 }
            class EmptyClass
            class SingleStableVal(val p1: Int)
            class SingleParamProp<T>(val p1: T)
            class SingleParamNonProp<T>(p1: T) { val p2 = p1.hashCode() }
            class DoubleParamSingleProp<T, V>(val p1: T, p2: V) { val p3 = p2.hashCode() }
            class X<T>(val p1: List<T>)
            class NonBackingFieldUnstableProp {
                val p1: Unstable get() { TODO() }
            }
            class NonBackingFieldUnstableVarProp {
                var p1: Unstable
                    get() { TODO() }
                    set(value) { }
            }
            class StableDelegateProp {
                var p1 by StableDelegate()
            }
            class UnstableDelegateProp {
                var p1 by UnstableDelegate()
            }
        """
    )

    @Test
    fun testStabilityPropagationOfVariousTypes() = verifyGoldenCrossModuleComposeIrTransform(
        """
            package a
            import androidx.compose.runtime.Stable
            import kotlin.reflect.KProperty

            @Stable
            class StableDelegate {
                operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
                }
                operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                    return 10
                }
            }

            class UnstableDelegate {
                var value: Int = 0
                operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
                    this.value = value
                }
                operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                    return 10
                }
            }
            class UnstableClass {
                var value: Int = 0
            }
            class StableClass

            class EmptyClass
            class SingleStableValInt(val p1: Int)
            class SingleStableVal(val p1: StableClass)
            class SingleParamProp<T>(val p1: T)
            class SingleParamNonProp<T>(p1: T) { val p2 = p1.hashCode() }
            class DoubleParamSingleProp<T, V>(val p1: T, p2: V) { val p3 = p2.hashCode() }
            class X<T>(val p1: List<T>)
            class NonBackingFieldUnstableVal {
                val p1: UnstableClass get() { TODO() }
            }
            class NonBackingFieldUnstableVar {
                var p1: UnstableClass
                    get() { TODO() }
                    set(value) { }
            }
            class StableDelegateProp {
                var p1 by StableDelegate()
            }
            class UnstableDelegateProp {
                var p1 by UnstableDelegate()
            }
            fun used(x: Any?) {}
        """,
        """
            import a.*
            import androidx.compose.runtime.Composable

            @Composable fun A(y: Any? = null) {
                used(y)
                A()
                A(EmptyClass())
                A(SingleStableValInt(123))
                A(SingleStableVal(StableClass()))
                A(SingleParamProp(StableClass()))
                A(SingleParamProp(UnstableClass()))
                A(SingleParamNonProp(StableClass()))
                A(SingleParamNonProp(UnstableClass()))
                A(DoubleParamSingleProp(StableClass(), StableClass()))
                A(DoubleParamSingleProp(UnstableClass(), StableClass()))
                A(DoubleParamSingleProp(StableClass(), UnstableClass()))
                A(DoubleParamSingleProp(UnstableClass(), UnstableClass()))
                A(X(listOf(StableClass())))
                A(X(listOf(StableClass())))
                A(NonBackingFieldUnstableVal())
                A(NonBackingFieldUnstableVar())
                A(StableDelegateProp())
                A(UnstableDelegateProp())
            }
        """
    )

    @Test
    fun testStabilityPropagationOfVariousTypesInSameModule() =
        verifyGoldenCrossModuleComposeIrTransform(
            """
            package a
            import androidx.compose.runtime.Stable
            import kotlin.reflect.KProperty

            @Stable
            class StableDelegate {
                operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
                }
                operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                    return 10
                }
            }

            class UnstableDelegate {
                var value: Int = 0
                operator fun setValue(thisObj: Any?, property: KProperty<*>, value: Int) {
                    this.value = value
                }
                operator fun getValue(thisObj: Any?, property: KProperty<*>): Int {
                    return 10
                }
            }
            class UnstableClass {
                var value: Int = 0
            }
            class StableClass

            class EmptyClass
            class SingleStableValInt(val p1: Int)
            class SingleStableVal(val p1: StableClass)
            class SingleParamProp<T>(val p1: T)
            class SingleParamNonProp<T>(p1: T) { val p2 = p1.hashCode() }
            class DoubleParamSingleProp<T, V>(val p1: T, p2: V) { val p3 = p2.hashCode() }
            class NonBackingFieldUnstableVal {
                val p1: UnstableClass get() { TODO() }
            }
            class NonBackingFieldUnstableVar {
                var p1: UnstableClass
                    get() { TODO() }
                    set(value) { }
            }
            fun used(x: Any?) {}
        """,
            """
            import a.*
            import androidx.compose.runtime.Composable

            class X<T>(val p1: List<T>)
            class StableDelegateProp {
                var p1 by StableDelegate()
            }
            class UnstableDelegateProp {
                var p1 by UnstableDelegate()
            }
            @Composable fun A(y: Any) {
                used(y)
                A(X(listOf(StableClass())))
                A(StableDelegateProp())
                A(UnstableDelegateProp())
            }
        """
        )

    @Test
    fun testEmptyClassAcrossModules() = verifyGoldenCrossModuleComposeIrTransform(
        """
            package a
            class Wrapper<T>(value: T) {
              fun make(): T = error("")
            }
            class Foo
            fun used(x: Any?) {}
        """,
        """
            import a.*
            import androidx.compose.runtime.Composable

            @Composable fun A(y: Any) {
                used(y)
                A(Wrapper(Foo()))
            }
        """
    )

    @Test
    fun testLocalParameterBasedTypeParameterSubstitution() =
        verifyGoldenCrossModuleComposeIrTransform(
            """
            package a
            import androidx.compose.runtime.Composable
            class Wrapper<T>(val value: T)
            @Composable fun A(y: Any) {}
        """,
            """
            import a.*
            import androidx.compose.runtime.Composable

            @Composable fun <V> B(value: V) {
                A(Wrapper(value))
            }
            @Composable fun <T> X(items: List<T>, itemContent: @Composable (T) -> Unit) {
                for (item in items) itemContent(item)
            }
            @Composable fun C(items: List<String>) {
                X(items) { item ->
                    A(item)
                    A(Wrapper(item))
                }
            }
        """
    )

    @Test
    fun testSingleVarVersusValProperty() = assertTransform(
        """
            class Stable(val bar: Int)
            class Unstable(var bar: Int)
        """
    )

    @Test
    fun testComposableCall() = assertTransform(
        """
            import androidx.compose.runtime.Composable

            class Foo
            @Composable fun A(y: Int, x: Any) {
                used(y)
                B(x)
            }
            @Composable fun B(x: Any) {
                used(x)
            }
        """
    )

    @Test
    fun testComposableCallWithUnstableFinalClassInSameModule() = assertTransform(
        """
            import androidx.compose.runtime.Composable

            class Foo(var bar: Int = 0)
            @Composable fun A(y: Int, x: Foo) {
                used(y)
                B(x)
            }
            @Composable fun B(x: Any) {
                used(x)
            }
        """
    )

    private fun assertStability(
        @Language("kotlin")
        classDefSrc: String,
        stability: String,
        externalTypes: Set<String> = emptySet(),
    ) {
        val source = """
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.setValue
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.Stable
            import androidx.compose.runtime.State
            import kotlin.reflect.KProperty

            $classDefSrc

            class Unstable { var value: Int = 0 }
        """.trimIndent()

        val files = listOf(SourceFile("Test.kt", source))
        val irModule = compileToIr(files)
        val irClass = irModule.files.last().declarations.first() as IrClass
        val externalTypeMatchers = externalTypes.map { FqNameMatcher(it) }.toSet()
        val stabilityInferencer = StabilityInferencer(externalTypeMatchers)
        val classStability = stabilityInferencer.stabilityOf(irClass.defaultType as IrType)

        assertEquals(
            stability,
            classStability.toString()
        )
    }

    private fun assertStability(
        @Language("kotlin")
        externalSrc: String,
        @Language("kotlin")
        classDefSrc: String,
        stability: String,
        dumpClasses: Boolean = false,
        externalTypes: Set<String> = emptySet(),
        packageName: String = "dependency"
    ) {
        val irModule = buildModule(externalSrc, classDefSrc, dumpClasses, packageName)
        val irClass = irModule.files.last().declarations.first() as IrClass
        val externalTypeMatchers = externalTypes.map { FqNameMatcher(it) }.toSet()
        val classStability =
            StabilityInferencer(externalTypeMatchers).stabilityOf(irClass.defaultType as IrType)

        assertEquals(
            stability,
            classStability.toString()
        )
    }

    @Test
    fun testTransformCombinedClassWithUnstableParametrizedClass() {
        verifyCrossModuleComposeIrTransform(
            dependencySource = """
                class SomeFoo(val value: Int)
                class ParametrizedFoo<K>(var value: K)
            """,
            source = """
                class CombinedUnstable<T>(val first: T, val second: ParametrizedFoo<SomeFoo>)
            """,
            expectedTransformed = """
                @StabilityInferred(parameters = 1)
                class CombinedUnstable<T> (val first: T, val second: ParametrizedFoo<SomeFoo>) {
                  static val %stable: Int = ParametrizedFoo.%stable or 0
                }
            """
        )
    }

    @Test
    fun testTransformCombinedClassWithRuntimeStableParametrizedClass() {
        verifyCrossModuleComposeIrTransform(
            dependencySource = """
            class SomeFoo(val value: Int)
            class ParametrizedFoo<K>(val value: K)
        """,
            source = """
            class CombinedStable<T>(val first: T, val second: ParametrizedFoo<SomeFoo>)
        """,
            expectedTransformed = """
            @StabilityInferred(parameters = 1)
            class CombinedStable<T> (val first: T, val second: ParametrizedFoo<SomeFoo>) {
              static val %stable: Int = SomeFoo.%stable or ParametrizedFoo.%stable or 0
            }
        """
        )
    }

    @Test
    fun testTransformCombinedClassWithMultiplyTypeParameters() {
        verifyCrossModuleComposeIrTransform(
            dependencySource = """
            class SomeFoo(val value: Int)
            class ParametrizedFoo<K>(val value: K)
        """,
            source = """
            class CombinedStable<T, K>(val first: T, val second: ParametrizedFoo<K>)
        """,
            expectedTransformed = """
            @StabilityInferred(parameters = 3)
            class CombinedStable<T, K> (val first: T, val second: ParametrizedFoo<K>) {
              static val %stable: Int = 0 or ParametrizedFoo.%stable or 0
            }
        """
        )
    }

    private fun assertStability(
        externalSrc: String,
        localSrc: String,
        expression: String,
        stability: String,
        dumpClasses: Boolean = false,
        externalTypes: Set<String> = emptySet()
    ) {
        val irModule = buildModule(
            externalSrc,
            """
                $localSrc

                fun TestFunction() = $expression
            """.trimIndent(),
            dumpClasses
        )
        val irTestFn = irModule
            .files
            .last()
            .declarations
            .filterIsInstance<IrSimpleFunction>()
            .first { it.name.asString() == "TestFunction" }

        val lastStatement = irTestFn.body!!.statements.last()
        val irExpr = when (lastStatement) {
            is IrReturn -> lastStatement.value
            is IrExpression -> lastStatement
            else -> error("unexpected statement: $lastStatement")
        }
        val externalTypeMatchers = externalTypes.map { FqNameMatcher(it) }.toSet()
        val exprStability = StabilityInferencer(externalTypeMatchers).stabilityOf(irExpr)

        assertEquals(
            stability,
            exprStability.toString()
        )
    }

    private fun buildModule(
        @Language("kotlin")
        externalSrc: String,
        @Language("kotlin")
        localSrc: String,
        dumpClasses: Boolean = false,
        packageName: String = "dependency"
    ): IrModuleFragment {
        val dependencyFileName = "Test_REPLACEME_${uniqueNumber++}"
        val dependencySrc = """
            package $packageName
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.setValue
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.Stable
            import androidx.compose.runtime.State
            import kotlin.reflect.KProperty

            class UnusedClassToEnsurePackageGetsGenerated

            $externalSrc
        """.trimIndent()

        classLoader(dependencySrc, dependencyFileName, dumpClasses)
            .allGeneratedFiles
            .also {
                // Write the files to the class directory so they can be used by the next module
                // and the application
                it.writeToDir(classesDirectory.root)
            }

        val source = """
            import $packageName.*
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.getValue
            import androidx.compose.runtime.setValue
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.Stable
            import androidx.compose.runtime.State
            import kotlin.reflect.KProperty

            $localSrc
        """.trimIndent()

        val files = listOf(SourceFile("Test.kt", source))
        return compileToIr(files, listOf(classesDirectory.root))
    }

    private fun assertTransform(
        @Language("kotlin")
        checked: String,
        unchecked: String = "",
        dumpTree: Boolean = false
    ) = verifyGoldenComposeIrTransform(
        checked,
        """
            $unchecked
            fun used(x: Any?) {}
        """,
        dumpTree = dumpTree
    )
}
