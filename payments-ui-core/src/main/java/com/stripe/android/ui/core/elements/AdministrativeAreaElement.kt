package com.stripe.android.ui.core.elements

import androidx.annotation.RestrictTo
import com.stripe.android.uicore.elements.DropdownFieldController

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
data class AdministrativeAreaElement(
    override val identifier: IdentifierSpec,
    override val controller: DropdownFieldController
) : SectionSingleFieldElement(identifier)
