package com.gts.trackmypath.data.network

import com.gts.trackmypath.common.Result

interface FlickrClient {

    suspend fun searchPhoto(
        lat: String,
        lon: String,
        radius: String
    ): Result<List<PhotoResponseEntity>>
}
