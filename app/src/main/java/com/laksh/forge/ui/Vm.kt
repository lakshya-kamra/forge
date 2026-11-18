package com.laksh.forge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository

/**
 * Manual DI. The app has one repository and a handful of view models; a full DI framework
 * would cost more in build complexity than it saves here.
 */
inline fun <reified VM : ViewModel> forgeFactory(
    crossinline create: (ForgeRepository) -> VM
): ViewModelProvider.Factory = viewModelFactory {
    initializer { create(ForgeApp.instance.repository) }
}

val CreationExtras.repo: ForgeRepository get() = ForgeApp.instance.repository
