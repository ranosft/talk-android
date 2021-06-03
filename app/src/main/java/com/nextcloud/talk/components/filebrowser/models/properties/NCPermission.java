/*
 * Nextcloud Talk application
 *
 * @author Mario Danic
 * Copyright (C) 2017-2019 Mario Danic <mario@lovelyhq.com>
 * @author Marcel Hibbe
 * Copyright (C) 2021 Marcel Hibbe <dev@mhibbe.de>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextcloud.talk.components.filebrowser.models.properties;

import android.text.TextUtils;
import android.util.Log;

import com.nextcloud.talk.components.filebrowser.webdav.DavUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

import at.bitfire.dav4android.Property;
import at.bitfire.dav4android.PropertyFactory;
import at.bitfire.dav4android.XmlUtils;

public class NCPermission implements Property {
    public static final Name NAME = new Name(DavUtils.OC_NAMESPACE, DavUtils.EXTENDED_PROPERTY_NAME_PERMISSIONS);

    private String ncPermission;

    private NCPermission(String p) {
        ncPermission = p;
    }

    public String getNcPermission() {
        return this.ncPermission;
    }

    public void setNcPermission(String ncPermission) {
        this.ncPermission = ncPermission;
    }

    public static class Factory implements PropertyFactory {

        @Nullable
        @Override
        public Property create(@NotNull XmlPullParser xmlPullParser) {
            try {
                String text = XmlUtils.INSTANCE.readText(xmlPullParser);
                if (!TextUtils.isEmpty(text)) {
                    return new NCPermission(text);
                }
            } catch (IOException | XmlPullParserException e) {
                Log.e("NCPermission", "failed to create property", e);
            }

            return new NCPermission("");
        }

        @NotNull
        @Override
        public Name getName() {
            return NAME;
        }
    }
}