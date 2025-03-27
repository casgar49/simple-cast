package com.dupontgu.simplecast

sealed class CastResult<T:Any> {

    data class Success<T : Any>(val result:T):CastResult<T>()

    data class Error<T:Any>(val error:String):CastResult<T>()

    companion object{
        val emptySuccess = Success(Unit)
    }
}