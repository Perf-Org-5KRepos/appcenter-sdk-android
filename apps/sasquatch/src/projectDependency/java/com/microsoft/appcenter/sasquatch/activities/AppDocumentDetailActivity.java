/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.appcenter.sasquatch.activities;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.widget.TextView;

import com.microsoft.appcenter.sasquatch.R;

public class AppDocumentDetailActivity extends AppCompatActivity {

    private TextView mDocumentId;

    private TextView mDocumentContents;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_document_detail);

        mDocumentId = findViewById(R.id.app_document_id);
        mDocumentContents = findViewById(R.id.app_document_content);
        Intent intent = getIntent();
        String documentId = intent.getStringExtra("documentId");
        String documentContents = intent.getStringExtra("documentContent");
        mDocumentId.setText(documentId);
        mDocumentContents.setText(documentContents);
    }
}