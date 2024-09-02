package com.fireshare.tweet.viewmodel

import androidx.lifecycle.ViewModel
import com.fireshare.tweet.datamodel.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserViewModel(): ViewModel()
{
    private var _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> get() = _user.asStateFlow()
}