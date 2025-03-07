/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

// This file was generated automatically. See compiler/ir/ir.tree/tree-generator/ReadMe.md.
// DO NOT MODIFY IT MANUALLY.

package org.jetbrains.kotlin.ir.declarations

import org.jetbrains.kotlin.descriptors.Modality

/**
 * A non-leaf IR tree element.
 * @sample org.jetbrains.kotlin.ir.generator.IrTree.overridableMember
 */
interface IrOverridableMember : IrDeclaration, IrDeclarationWithVisibility,
        IrDeclarationWithName, IrSymbolOwner {
    var modality: Modality
}
