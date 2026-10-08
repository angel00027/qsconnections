package com.example.qsconnection.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Int = 0,
    
    @ColumnInfo(name = "name")
    val name: String,
    
    @ColumnInfo(name = "ip")
    val ip: String,
    
    @ColumnInfo(name = "port")
    val port: String,

    @ColumnInfo(name = "lastUsed")
    val lastUsed: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "mac")
    val mac: String? = null,

    @ColumnInfo(name = "username")
    val username: String? = null,

    @ColumnInfo(name = "password")
    val password: String? = null,

    // true = conexión sin usuario/contraseña (anónima)
    @ColumnInfo(name = "anonymous", defaultValue = "1")
    val anonymous: Boolean = true
)
