/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.cube;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.persistence.ResourceStore;
import org.apache.kylin.common.persistence.RootPersistentEntity;
import org.apache.kylin.cube.model.CubeDesc;
import org.apache.kylin.cube.model.DimensionDesc;
import org.apache.kylin.metadata.model.MeasureDesc;
import org.apache.kylin.metadata.model.SegmentStatusEnum;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.metadata.realization.IRealization;
import org.apache.kylin.metadata.realization.RealizationStatusEnum;
import org.apache.kylin.metadata.realization.RealizationType;
import org.apache.kylin.metadata.realization.SQLDigest;

@JsonAutoDetect(fieldVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, isGetterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
public class CubeInstance extends RootPersistentEntity implements IRealization {

    public static CubeInstance create(String cubeName, String projectName, CubeDesc cubeDesc) {
        CubeInstance cubeInstance = new CubeInstance();

        cubeInstance.setConfig(cubeDesc.getConfig());
        cubeInstance.setName(cubeName);
        cubeInstance.setDescName(cubeDesc.getName());
        cubeInstance.setCreateTimeUTC(System.currentTimeMillis());
        cubeInstance.setSegments(new ArrayList<CubeSegment>());
        cubeInstance.setStatus(RealizationStatusEnum.DISABLED);
        cubeInstance.updateRandomUuid();
        cubeInstance.setProjectName(projectName);

        return cubeInstance;
    }

    @JsonIgnore
    private KylinConfig config;
    @JsonProperty("name")
    private String name;
    @JsonProperty("owner")
    private String owner;
    @JsonProperty("version")
    private String version; // user info only, we don't do version control
    @JsonProperty("descriptor")
    private String descName;
    // Mark cube priority for query
    @JsonProperty("cost")
    private int cost = 50;
    @JsonProperty("status")
    private RealizationStatusEnum status;

    @JsonManagedReference
    @JsonProperty("segments")
    private List<CubeSegment> segments = new ArrayList<CubeSegment>();

    @JsonProperty("create_time_utc")
    private long createTimeUTC;

    private String projectName;
    
    public List<CubeSegment> getBuildingSegments() {
        List<CubeSegment> buildingSegments = new ArrayList<CubeSegment>();
        if (null != segments) {
            for (CubeSegment segment : segments) {
                if (SegmentStatusEnum.NEW == segment.getStatus() || SegmentStatusEnum.READY_PENDING == segment.getStatus()) {
                    buildingSegments.add(segment);
                }
            }
        }

        return buildingSegments;
    }

    public long getAllocatedEndDate() {
        if (null == segments || segments.size() == 0) {
            return 0;
        }

        Collections.sort(segments);

        return segments.get(segments.size() - 1).getDateRangeEnd();
    }

    public long getAllocatedStartDate() {
        if (null == segments || segments.size() == 0) {
            return 0;
        }

        Collections.sort(segments);

        return segments.get(0).getDateRangeStart();
    }

    public List<CubeSegment> getMergingSegments() {
        return this.getMergingSegments(null);
    }

    public List<CubeSegment> getMergingSegments(CubeSegment cubeSegment) {
        CubeSegment buildingSegment;
        if (cubeSegment == null) { // this path goes tests only
            List<CubeSegment> buildingSegments = getBuildingSegments();
            if (buildingSegments.size() == 0) {
                return Collections.emptyList();
            }
            buildingSegment = buildingSegments.get(0);
        } else {
            buildingSegment = cubeSegment;
        }

        List<CubeSegment> mergingSegments = new ArrayList<CubeSegment>();
        if (null != this.segments) {
            for (CubeSegment segment : this.segments) {
                if (!buildingSegment.equals(segment) //
                        && buildingSegment.getDateRangeStart() <= segment.getDateRangeStart() && buildingSegment.getDateRangeEnd() >= segment.getDateRangeEnd()) {
                    mergingSegments.add(segment);
                }
            }
        }
        return mergingSegments;

    }

    public List<CubeSegment> getRebuildingSegments() {
        List<CubeSegment> buildingSegments = getBuildingSegments();
        if (buildingSegments.size() == 0) {
            return Collections.emptyList();
        } else {
            List<CubeSegment> rebuildingSegments = new ArrayList<CubeSegment>();
            if (null != this.segments) {
                long startDate = buildingSegments.get(0).getDateRangeStart();
                long endDate = buildingSegments.get(buildingSegments.size() - 1).getDateRangeEnd();
                for (CubeSegment segment : this.segments) {
                    if (segment.getStatus() == SegmentStatusEnum.READY) {
                        if (startDate >= segment.getDateRangeStart() && startDate < segment.getDateRangeEnd() && segment.getDateRangeEnd() < endDate) {
                            rebuildingSegments.add(segment);
                            continue;
                        }
                        if (startDate <= segment.getDateRangeStart() && endDate >= segment.getDateRangeEnd()) {
                            rebuildingSegments.add(segment);
                            continue;
                        }
                    }
                }
            }

            return rebuildingSegments;
        }
    }

    public CubeDesc getDescriptor() {
        return CubeDescManager.getInstance(config).getCubeDesc(descName);
    }

    public boolean isReady() {
        return getStatus() == RealizationStatusEnum.READY;
    }

    public String getResourcePath() {
        return concatResourcePath(name);
    }

    public static String concatResourcePath(String cubeName) {
        return ResourceStore.CUBE_RESOURCE_ROOT + "/" + cubeName + ".json";
    }

    @Override
    public String toString() {
        return getCanonicalName();
    }

    // ============================================================================

    @JsonProperty("size_kb")
    public long getSizeKB() {
        long sizeKb = 0L;

        for (CubeSegment cubeSegment : this.getSegments(SegmentStatusEnum.READY)) {
            sizeKb += cubeSegment.getSizeKB();
        }

        return sizeKb;
    }

    @JsonProperty("input_records_count")
    public long getInputRecordCount() {
        long sizeRecordCount = 0L;

        for (CubeSegment cubeSegment : this.getSegments(SegmentStatusEnum.READY)) {
            sizeRecordCount += cubeSegment.getInputRecords();
        }

        return sizeRecordCount;
    }

    @JsonProperty("input_records_size")
    public long getInputRecordSize() {
        long sizeRecordSize = 0L;

        for (CubeSegment cubeSegment : this.getSegments(SegmentStatusEnum.READY)) {
            sizeRecordSize += cubeSegment.getInputRecordsSize();
        }

        return sizeRecordSize;
    }

    public KylinConfig getConfig() {
        return config;
    }

    public void setConfig(KylinConfig config) {
        this.config = config;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getCanonicalName() {
        return getType() + "[name=" + name + "]";
    }

    @Override
    public String getFactTable() {
        return this.getDescriptor().getFactTable();
    }

    @Override
    public List<MeasureDesc> getMeasures() {
        return getDescriptor().getMeasures();
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescName() {
        return descName.toUpperCase();
    }

    public String getOriginDescName() {
        return descName;
    }

    public void setDescName(String descName) {
        this.descName = descName;
    }

    public int getCost() {
        return cost;
    }

    public void setCost(int cost) {
        this.cost = cost;
    }

    public RealizationStatusEnum getStatus() {
        return status;
    }

    public void setStatus(RealizationStatusEnum status) {
        this.status = status;
    }

    public CubeSegment getFirstSegment() {
        if (this.segments == null || this.segments.size() == 0) {
            return null;
        } else {
            return this.segments.get(0);
        }
    }

    public CubeSegment getLatestReadySegment() {
        CubeSegment latest = null;
        for (int i = segments.size() - 1; i >= 0; i--) {
            CubeSegment seg = segments.get(i);
            if (seg.getStatus() != SegmentStatusEnum.READY)
                continue;
            if (latest == null || latest.getDateRangeEnd() < seg.getDateRangeEnd()) {
                latest = seg;
            }
        }
        return latest;
    }

    public List<CubeSegment> getSegments() {
        return segments;
    }

    public List<CubeSegment> getSegments(SegmentStatusEnum status) {
        List<CubeSegment> result = new ArrayList<CubeSegment>();

        for (CubeSegment segment : segments) {
            if (segment.getStatus() == status) {
                result.add(segment);
            }
        }

        return result;
    }

    public List<CubeSegment> getSegment(SegmentStatusEnum status) {
        List<CubeSegment> result = Lists.newArrayList();
        for (CubeSegment segment : segments) {
            if (segment.getStatus() == status) {
                result.add(segment);
            }
        }
        return result;
    }

    public CubeSegment getSegment(String name, SegmentStatusEnum status) {
        for (CubeSegment segment : segments) {
            if ((null != segment.getName() && segment.getName().equals(name)) && segment.getStatus() == status) {
                return segment;
            }
        }

        return null;
    }

    public void setSegments(List<CubeSegment> segments) {
        this.segments = segments;
    }

    public CubeSegment getSegmentById(String segmentId) {
        for (CubeSegment segment : segments) {
            if (Objects.equal(segment.getUuid(), segmentId)) {
                return segment;
            }
        }
        return null;
    }

    public long getCreateTimeUTC() {
        return createTimeUTC;
    }

    public void setCreateTimeUTC(long createTimeUTC) {
        this.createTimeUTC = createTimeUTC;
    }

    public long[] getDateRange() {
        List<CubeSegment> readySegments = getSegment(SegmentStatusEnum.READY);
        if (readySegments.isEmpty()) {
            return new long[] { 0L, 0L };
        }
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (CubeSegment segment : readySegments) {
            if (segment.getDateRangeStart() < start) {
                start = segment.getDateRangeStart();
            }
            if (segment.getDateRangeEnd() > end) {
                end = segment.getDateRangeEnd();
            }
        }
        return new long[] { start, end };
    }

    @Override
    public boolean isCapable(SQLDigest digest) {
        return CubeCapabilityChecker.check(this, digest, true);
    }

    @Override
    public int getCost(SQLDigest digest) {
        return 0;
    }

    @Override
    public RealizationType getType() {
        return RealizationType.CUBE;
    }

    @Override
    public List<TblColRef> getAllColumns() {
        return Lists.newArrayList(getDescriptor().listAllColumns());
    }

    public List<TblColRef> getDimensions() {
        List<TblColRef> ret = Lists.newArrayList();
        for (DimensionDesc dim : getDescriptor().getDimensions()) {
            for (TblColRef colRef : dim.getColumnRefs()) {
                ret.add(colRef);
            }
        }
        return ret;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

}