/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir

import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.synthetic.FirSyntheticPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.utils.*
import org.jetbrains.kotlin.fir.declarations.utils.isStatic
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.references.FirSuperReference
import org.jetbrains.kotlin.fir.resolve.*
import org.jetbrains.kotlin.fir.resolve.calls.ExpressionReceiverValue
import org.jetbrains.kotlin.fir.resolve.calls.FirSyntheticFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.calls.ImplicitReceiverValue
import org.jetbrains.kotlin.fir.resolve.calls.ReceiverValue
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeLookupTag
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.AbstractTypeChecker

abstract class FirModuleVisibilityChecker : FirSessionComponent {
    abstract fun isInFriendModule(declaration: FirMemberDeclaration): Boolean

    class Standard(val session: FirSession) : FirModuleVisibilityChecker() {
        override fun isInFriendModule(declaration: FirMemberDeclaration): Boolean {
            val useSiteModuleData = session.moduleData
            return when (declaration.moduleData) {
                useSiteModuleData,
                in useSiteModuleData.friendDependencies,
                in useSiteModuleData.dependsOnDependencies -> true

                else -> false
            }
        }
    }
}

abstract class FirVisibilityChecker : FirSessionComponent {
    @NoMutableState
    object Default : FirVisibilityChecker() {
        override fun platformVisibilityCheck(
            declarationVisibility: Visibility,
            symbol: FirBasedSymbol<*>,
            useSiteFile: FirFile,
            containingDeclarations: List<FirDeclaration>,
            dispatchReceiver: ReceiverValue?,
            session: FirSession,
            isCallToPropertySetter: Boolean,
            supertypeSupplier: SupertypeSupplier
        ): Boolean {
            return true
        }

        override fun platformOverrideVisibilityCheck(
            candidateInDerivedClass: FirBasedSymbol<*>,
            symbolInBaseClass: FirBasedSymbol<*>,
            visibilityInBaseClass: Visibility,
        ): Boolean {
            return true
        }
    }

    fun isClassLikeVisible(
        declaration: FirClassLikeDeclaration,
        session: FirSession,
        useSiteFile: FirFile,
        containingDeclarations: List<FirDeclaration>,
    ): Boolean {
        return isVisible(
            declaration,
            session,
            useSiteFile,
            containingDeclarations,
            dispatchReceiver = null,
            isCallToPropertySetter = false,
            staticQualifierClassForCallable = null,
            skipCheckForContainingClassVisibility = false,
            supertypeSupplier = SupertypeSupplier.Default
        )
    }

    fun isVisible(
        declaration: FirMemberDeclaration,
        session: FirSession,
        useSiteFile: FirFile,
        containingDeclarations: List<FirDeclaration>,
        dispatchReceiver: ReceiverValue?,
        isCallToPropertySetter: Boolean = false,
        staticQualifierClassForCallable: FirRegularClass? = null,
        // There's no need to check if containing class is visible in case we check if a member might be overridden in a subclass
        // because visibility for its supertype that contain overridden member is being checked when resolving the type reference.
        // Such flag is not necessary in FE1.0, since there are full structure of fake overrides and containing declaration for overridden
        // is always visible since it's a supertype of a derived class.
        skipCheckForContainingClassVisibility: Boolean = false,
        supertypeSupplier: SupertypeSupplier = SupertypeSupplier.Default
    ): Boolean {
        if (!isSpecificDeclarationVisible(
                if (declaration is FirCallableDeclaration) declaration.originalOrSelf() else declaration,
                session,
                useSiteFile,
                containingDeclarations,
                dispatchReceiver,
                isCallToPropertySetter,
                supertypeSupplier
            )
        ) {
            return false
        }

        if (skipCheckForContainingClassVisibility) return true

        if (staticQualifierClassForCallable != null) {
            return isSpecificDeclarationVisible(
                staticQualifierClassForCallable,
                session,
                useSiteFile,
                containingDeclarations,
                dispatchReceiver = null,
                isCallToPropertySetter,
                supertypeSupplier
            )
        }
        val parentClass = declaration.containingNonLocalClass(
            session,
            dispatchReceiver,
            containingDeclarations,
            supertypeSupplier
        ) ?: return true
        return generateSequence(parentClass) { it.containingNonLocalClass(session) }.all { parent ->
            isSpecificDeclarationVisible(
                parent,
                session,
                useSiteFile,
                containingDeclarations,
                dispatchReceiver = null,
                isCallToPropertySetter,
                supertypeSupplier
            )
        }
    }

    fun isVisibleForOverriding(
        candidateInDerivedClass: FirMemberDeclaration,
        candidateInBaseClass: FirMemberDeclaration
    ): Boolean = isVisibleForOverriding(candidateInDerivedClass.moduleData, candidateInDerivedClass.symbol, candidateInBaseClass)

    fun isVisibleForOverriding(
        derivedClassModuleData: FirModuleData,
        symbolFromDerivedClass: FirBasedSymbol<*>,
        candidateInBaseClass: FirMemberDeclaration,
    ): Boolean = when (candidateInBaseClass.visibility) {
        Visibilities.Internal -> {
            candidateInBaseClass.moduleData == derivedClassModuleData ||
                    derivedClassModuleData.session.moduleVisibilityChecker?.isInFriendModule(candidateInBaseClass) == true
        }

        Visibilities.Private, Visibilities.PrivateToThis -> false
        Visibilities.Protected -> true
        else -> platformOverrideVisibilityCheck(symbolFromDerivedClass, candidateInBaseClass.symbol, candidateInBaseClass.visibility)
    }

    private fun FirMemberDeclaration.containingNonLocalClass(
        session: FirSession,
        dispatchReceiverValue: ReceiverValue?,
        containingUseSiteDeclarations: List<FirDeclaration>,
        supertypeSupplier: SupertypeSupplier
    ): FirClassLikeDeclaration? {
        return when (this) {
            is FirCallableDeclaration -> {
                if (dispatchReceiverValue != null) {
                    val baseReceiverType = dispatchReceiverClassTypeOrNull()
                    if (baseReceiverType != null) {
                        dispatchReceiverValue.type.findClassRepresentation(baseReceiverType, session)?.toSymbol(session)?.fir?.let {
                            return it
                        }
                    }
                }

                val containingLookupTag = this.containingClassLookupTag()
                val containingClass = containingLookupTag?.toSymbol(session)?.fir

                if (isStatic && containingClass != null) {
                    containingUseSiteDeclarations.firstNotNullOfOrNull {
                        if (it !is FirClass) return@firstNotNullOfOrNull null
                        it.takeIf { it.isSubclassOf(containingLookupTag, session, isStrict = false, supertypeSupplier) }
                    }?.let { return it }
                }

                containingClass
            }
            is FirClassLikeDeclaration -> containingNonLocalClass(session)
        }
    }

    private fun FirClassLikeDeclaration.containingNonLocalClass(session: FirSession): FirClassLikeDeclaration? {
        return when (this) {
            is FirClass -> {
                if (isLocal) return null

                this.classId.outerClassId?.let { session.symbolProvider.getClassLikeSymbolByClassId(it)?.fir }
            }
            // Currently, type aliases are only top-level
            is FirTypeAlias -> null
        }
    }

    private fun isSpecificDeclarationVisible(
        declaration: FirMemberDeclaration,
        session: FirSession,
        useSiteFile: FirFile,
        containingDeclarations: List<FirDeclaration>,
        dispatchReceiver: ReceiverValue?,
        isCallToPropertySetter: Boolean = false,
        supertypeSupplier: SupertypeSupplier
    ): Boolean {
        val symbol = declaration.symbol
        val provider = session.firProvider

        return when (declaration.visibility) {
            Visibilities.Internal -> {
                declaration.moduleData == session.moduleData || session.moduleVisibilityChecker?.isInFriendModule(declaration) == true
            }
            Visibilities.Private, Visibilities.PrivateToThis -> {
                val ownerLookupTag = symbol.getOwnerLookupTag()
                if (declaration.moduleData == session.moduleData) {
                    when {
                        ownerLookupTag == null -> {
                            val candidateFile = when (symbol) {
                                is FirSyntheticFunctionSymbol -> {
                                    // SAM case
                                    val classId = ClassId(symbol.callableId.packageName, symbol.callableId.callableName)
                                    provider.getFirClassifierContainerFile(classId)
                                }
                                is FirClassLikeSymbol<*> -> provider.getFirClassifierContainerFileIfAny(symbol)
                                is FirCallableSymbol<*> -> provider.getFirCallableContainerFile(symbol)
                                else -> null
                            }
                            // Top-level: visible in file
                            candidateFile == useSiteFile
                        }
                        declaration is FirConstructor && declaration.isFromSealedClass -> {
                            // Sealed class constructor: visible in same package
                            declaration.symbol.callableId.packageName == useSiteFile.packageFqName
                        }
                        else -> {
                            // Member: visible inside parent class, including all its member classes
                            canSeePrivateMemberOf(
                                symbol,
                                containingDeclarations,
                                ownerLookupTag,
                                dispatchReceiver,
                                isVariableOrNamedFunction = symbol.isVariableOrNamedFunction(),
                                session
                            )
                        }
                    }
                } else {
                    declaration is FirSimpleFunction && declaration.isAllowedToBeAccessedFromOutside()
                }
            }

            Visibilities.Protected -> {
                val ownerId = symbol.getOwnerLookupTag()
                ownerId != null && canSeeProtectedMemberOf(
                    symbol, containingDeclarations, dispatchReceiver, ownerId, session,
                    isVariableOrNamedFunction = symbol.isVariableOrNamedFunction(),
                    symbol.fir is FirSyntheticPropertyAccessor,
                    supertypeSupplier
                )
            }

            else -> platformVisibilityCheck(
                declaration.visibility,
                symbol,
                useSiteFile,
                containingDeclarations,
                dispatchReceiver,
                session,
                isCallToPropertySetter,
                supertypeSupplier
            )
        }
    }

    protected abstract fun platformVisibilityCheck(
        declarationVisibility: Visibility,
        symbol: FirBasedSymbol<*>,
        useSiteFile: FirFile,
        containingDeclarations: List<FirDeclaration>,
        dispatchReceiver: ReceiverValue?,
        session: FirSession,
        isCallToPropertySetter: Boolean,
        supertypeSupplier: SupertypeSupplier
    ): Boolean

    protected abstract fun platformOverrideVisibilityCheck(
        candidateInDerivedClass: FirBasedSymbol<*>,
        symbolInBaseClass: FirBasedSymbol<*>,
        visibilityInBaseClass: Visibility,
    ): Boolean

    private fun canSeePrivateMemberOf(
        symbol: FirBasedSymbol<*>,
        containingDeclarationOfUseSite: List<FirDeclaration>,
        ownerLookupTag: ConeClassLikeLookupTag,
        dispatchReceiver: ReceiverValue?,
        isVariableOrNamedFunction: Boolean,
        session: FirSession
    ): Boolean {
        ownerLookupTag.ownerIfCompanion(session)?.let { companionOwnerLookupTag ->
            return canSeePrivateMemberOf(
                symbol,
                containingDeclarationOfUseSite,
                companionOwnerLookupTag,
                dispatchReceiver,
                isVariableOrNamedFunction,
                session
            )
        }

        // Note: private static symbols aren't accessible by use-site dispatch receiver
        // See e.g. diagnostics/tests/scopes/inheritance/statics/hidePrivateByPublic.kt,
        // private A.a becomes visible from outside without filtering static callables here
        if (dispatchReceiver != null && (symbol !is FirCallableSymbol || !symbol.isStatic)) {
            val fir = symbol.fir
            val dispatchReceiverParameterClassSymbol =
                (fir as? FirCallableDeclaration)
                    ?.propertyIfAccessor?.propertyIfBackingField
                    ?.dispatchReceiverClassLookupTagOrNull()?.toSymbol(session)
                    ?: return true

            val dispatchReceiverParameterClassLookupTag = dispatchReceiverParameterClassSymbol.toLookupTag()
            val dispatchReceiverValueOwnerLookupTag =
                dispatchReceiver.type.findClassRepresentation(
                    dispatchReceiverParameterClassLookupTag.constructClassType(
                        Array(dispatchReceiverParameterClassSymbol.fir.typeParameters.size) { ConeStarProjection },
                        isNullable = true
                    ),
                    session,
                )

            if (dispatchReceiverParameterClassLookupTag != dispatchReceiverValueOwnerLookupTag) return false
            if (fir.visibility == Visibilities.PrivateToThis) {
                when (dispatchReceiver) {
                    is ExpressionReceiverValue -> {
                        val explicitReceiver = dispatchReceiver.explicitReceiver
                        if (explicitReceiver !is FirThisReceiverExpression) {
                            return false
                        }
                        if (explicitReceiver.calleeReference.boundSymbol != dispatchReceiverParameterClassSymbol) {
                            return false
                        }
                    }
                    is ImplicitReceiverValue<*> -> {
                        if (dispatchReceiver.boundSymbol != dispatchReceiverParameterClassSymbol) {
                            return false
                        }
                    }
                }
            }
        }

        for (declaration in containingDeclarationOfUseSite) {
            if (declaration !is FirClass) continue
            val boundSymbol = declaration.symbol
            if (boundSymbol.toLookupTag() == ownerLookupTag) {
                return true
            }
        }

        return false
    }

    private fun ConeClassLikeLookupTag.ownerIfCompanion(session: FirSession): ConeClassLikeLookupTag? {
        if (classId.isLocal) return null
        val outerClassId = classId.outerClassId ?: return null
        val ownerSymbol = toSymbol(session) as? FirRegularClassSymbol

        if (ownerSymbol?.fir?.isCompanion == true) {
            return outerClassId.toLookupTag()
        }
        return null
    }

    private fun canSeeProtectedMemberOf(
        containingUseSiteClass: FirClass,
        dispatchReceiver: ReceiverValue?,
        ownerLookupTag: ConeClassLikeLookupTag,
        session: FirSession,
        isVariableOrNamedFunction: Boolean,
        isSyntheticProperty: Boolean,
        supertypeSupplier: SupertypeSupplier
    ): Boolean {
        dispatchReceiver?.ownerIfCompanion(session)?.let { companionOwnerLookupTag ->
            if (containingUseSiteClass.isSubclassOf(companionOwnerLookupTag, session, isStrict = false, supertypeSupplier)) return true
        }

        return when {
            !containingUseSiteClass.isSubclassOf(ownerLookupTag, session, isStrict = false, supertypeSupplier) -> false
            isVariableOrNamedFunction -> doesReceiverFitForProtectedVisibility(
                dispatchReceiver,
                containingUseSiteClass,
                ownerLookupTag,
                isSyntheticProperty,
                session
            )
            else -> true
        }
    }

    private fun doesReceiverFitForProtectedVisibility(
        dispatchReceiver: ReceiverValue?,
        containingUseSiteClass: FirClass,
        ownerLookupTag: ConeClassLikeLookupTag,
        isSyntheticProperty: Boolean,
        session: FirSession
    ): Boolean {
        if (dispatchReceiver == null) return true
        var dispatchReceiverType = dispatchReceiver.type
        if (dispatchReceiver is ExpressionReceiverValue) {
            val explicitReceiver = dispatchReceiver.explicitReceiver
            if (explicitReceiver is FirPropertyAccessExpression && explicitReceiver.calleeReference is FirSuperReference) {
                // Special 'super' case: type of this, not of super, should be taken for the check below
                dispatchReceiverType = explicitReceiver.dispatchReceiver.typeRef.coneType
            }
        }
        val typeCheckerState = session.typeContext.newTypeCheckerState(
            errorTypesEqualToAnything = false,
            stubTypesEqualToAnything = false
        )
        if (AbstractTypeChecker.isSubtypeOf(
                typeCheckerState,
                dispatchReceiverType.fullyExpandedType(session),
                containingUseSiteClass.symbol.constructStarProjectedType()
            )
        ) {
            return true
        }

        if (isSyntheticProperty) {
            return if (session.languageVersionSettings.supportsFeature(LanguageFeature.ImproveReportingDiagnosticsOnProtectedMembersOfBaseClass))
                containingUseSiteClass.classId.packageFqName == ownerLookupTag.classId.packageFqName
            else
                true
        }

        return false
    }

    private fun ReceiverValue?.ownerIfCompanion(session: FirSession): ConeClassLikeLookupTag? =
        (this?.type as? ConeClassLikeType)?.lookupTag?.ownerIfCompanion(session)

    // monitorEnter/monitorExit are the only functions which are accessed "illegally" (see kotlin/util/Synchronized.kt).
    // Since they are intrinsified in the codegen, FIR should treat it as visible.
    private fun FirSimpleFunction.isAllowedToBeAccessedFromOutside(): Boolean {
        if (!isFromLibrary) return false
        val packageName = symbol.callableId.packageName.asString()
        val name = name.asString()
        return packageName == "kotlin.jvm.internal.unsafe" &&
                (name == "monitorEnter" || name == "monitorExit")
    }

    protected fun canSeeProtectedMemberOf(
        usedSymbol: FirBasedSymbol<*>,
        containingDeclarationOfUseSite: List<FirDeclaration>,
        dispatchReceiver: ReceiverValue?,
        ownerLookupTag: ConeClassLikeLookupTag,
        session: FirSession,
        isVariableOrNamedFunction: Boolean,
        isSyntheticProperty: Boolean,
        supertypeSupplier: SupertypeSupplier
    ): Boolean {
        if (canSeePrivateMemberOf(
                usedSymbol,
                containingDeclarationOfUseSite,
                ownerLookupTag,
                dispatchReceiver,
                isVariableOrNamedFunction,
                session
            )
        ) return true

        for (containingDeclaration in containingDeclarationOfUseSite) {
            if (containingDeclaration is FirClass) {
                val boundSymbol = containingDeclaration.symbol
                if (canSeeProtectedMemberOf(
                        boundSymbol.fir,
                        dispatchReceiver,
                        ownerLookupTag,
                        session,
                        isVariableOrNamedFunction,
                        isSyntheticProperty,
                        supertypeSupplier
                    )
                ) return true
            } else if (containingDeclaration is FirFile) {
                if (isSyntheticProperty &&
                    session.languageVersionSettings.supportsFeature(LanguageFeature.ImproveReportingDiagnosticsOnProtectedMembersOfBaseClass) &&
                    containingDeclaration.packageFqName == ownerLookupTag.classId.packageFqName
                ) {
                    return true
                }
            }
        }

        return false
    }

    protected fun FirBasedSymbol<*>.packageFqName(): FqName {
        return when (this) {
            is FirClassLikeSymbol<*> -> classId.packageFqName
            is FirCallableSymbol<*> -> callableId.packageName
            else -> error("No package fq name for $this")
        }
    }
}

val FirSession.moduleVisibilityChecker: FirModuleVisibilityChecker? by FirSession.nullableSessionComponentAccessor()
val FirSession.visibilityChecker: FirVisibilityChecker by FirSession.sessionComponentAccessor()

fun FirBasedSymbol<*>.getOwnerLookupTag(): ConeClassLikeLookupTag? {
    return when (this) {
        is FirBackingFieldSymbol -> fir.propertySymbol.getOwnerLookupTag()
        is FirClassLikeSymbol<*> -> getContainingClassLookupTag()
        is FirCallableSymbol<*> -> containingClassLookupTag()
        is FirScriptSymbol -> null
        else -> error("Unsupported owner search for ${fir.javaClass}: ${fir.render()}")
    }
}

fun FirClassLikeSymbol<*>.getContainingClassLookupTag(): ConeClassLikeLookupTag? {
    return if (classId.isLocal) {
        (fir as? FirRegularClass)?.containingClassForLocal()
    } else {
        val ownerId = classId.outerClassId
        ownerId?.let { it.toLookupTag() }
    }
}

fun FirBasedSymbol<*>.isVariableOrNamedFunction(): Boolean {
    return this is FirVariableSymbol || this is FirNamedFunctionSymbol || this is FirPropertyAccessorSymbol
}
