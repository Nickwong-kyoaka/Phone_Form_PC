package com.phoneform.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Shared desktop controls keep every workflow on the same compact geometry. */
@Composable fun Button(
    onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,shape:Shape=RoundedCornerShape(8.dp),
    colors:ButtonColors=ButtonDefaults.buttonColors(),elevation:ButtonElevation?=ButtonDefaults.buttonElevation(),
    border:BorderStroke?=null,contentPadding:PaddingValues=PaddingValues(horizontal=14.dp,vertical=8.dp),
    interactionSource:MutableInteractionSource?=null,content:@Composable RowScope.()->Unit
) = androidx.compose.material3.Button(onClick,modifier,enabled,shape,colors,elevation,border,contentPadding,interactionSource,content)

@Composable fun OutlinedButton(
    onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,shape:Shape=RoundedCornerShape(8.dp),
    colors:ButtonColors=ButtonDefaults.outlinedButtonColors(),elevation:ButtonElevation?=null,
    border:BorderStroke?=ButtonDefaults.outlinedButtonBorder(enabled),contentPadding:PaddingValues=PaddingValues(horizontal=14.dp,vertical=8.dp),
    interactionSource:MutableInteractionSource?=null,content:@Composable RowScope.()->Unit
) = androidx.compose.material3.OutlinedButton(onClick,modifier,enabled,shape,colors,elevation,border,contentPadding,interactionSource,content)

@Composable fun TextButton(
    onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,shape:Shape=RoundedCornerShape(6.dp),
    colors:ButtonColors=ButtonDefaults.textButtonColors(),elevation:ButtonElevation?=null,border:BorderStroke?=null,
    contentPadding:PaddingValues=PaddingValues(horizontal=10.dp,vertical=6.dp),interactionSource:MutableInteractionSource?=null,
    content:@Composable RowScope.()->Unit
) = androidx.compose.material3.TextButton(onClick,modifier,enabled,shape,colors,elevation,border,contentPadding,interactionSource,content)
