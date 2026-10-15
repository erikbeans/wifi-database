import re
import uuid
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field, field_validator, model_validator

BSSID_PATTERN = re.compile(r"^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")


class AccessPointCreate(BaseModel):
    ssid: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=8, max_length=63)
    bssid: str | None = None
    security: str | None = Field(default=None, max_length=32)
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    consent_confirmed: bool

    @field_validator("ssid")
    @classmethod
    def ssid_byte_length(cls, value: str) -> str:
        encoded = len(value.encode("utf-8"))
        if encoded < 1 or encoded > 32:
            raise ValueError("ssid must be between 1 and 32 bytes")
        return value

    @field_validator("bssid")
    @classmethod
    def bssid_format(cls, value: str | None) -> str | None:
        if value is None:
            return None
        value = value.strip().lower()
        if not BSSID_PATTERN.match(value):
            raise ValueError("bssid must be a MAC address like aa:bb:cc:11:22:33")
        return value

    @field_validator("consent_confirmed")
    @classmethod
    def consent_required(cls, value: bool) -> bool:
        if not value:
            raise ValueError("consent_confirmed must be true to share a network password")
        return value


class PasswordCandidateCreate(BaseModel):
    password: str = Field(min_length=8, max_length=63)
    consent_confirmed: bool

    @field_validator("consent_confirmed")
    @classmethod
    def consent_required(cls, value: bool) -> bool:
        if not value:
            raise ValueError("consent_confirmed must be true to share a network password")
        return value


class VoteRequest(BaseModel):
    value: Literal[-1, 1]


class ReportRequest(BaseModel):
    ap_id: uuid.UUID | None = None
    password_id: uuid.UUID | None = None
    reason: str = Field(min_length=1, max_length=128)

    @model_validator(mode="after")
    def exactly_one_target(self) -> "ReportRequest":
        if (self.ap_id is None) == (self.password_id is None):
            raise ValueError("exactly one of ap_id or password_id is required")
        return self


class PasswordCandidate(BaseModel):
    id: uuid.UUID
    password: str
    up_votes: int
    down_votes: int
    score: int
    my_vote: int | None = None
    mine: bool = False
    can_delete: bool = False
    delete_until: datetime | None = None


class AccessPointDetail(BaseModel):
    id: uuid.UUID
    ssid: str
    bssid: str | None
    security: str | None
    latitude: float
    longitude: float
    passwords: list[PasswordCandidate]
    duplicate_password: bool | None = None
    mine: bool = False
    server_time: datetime
    can_delete: bool = False
    delete_until: datetime | None = None


class AccessPointSummary(BaseModel):
    id: uuid.UUID
    ssid: str
    bssid: str | None
    security: str | None
    latitude: float
    longitude: float
    distance_m: float | None = None
    password_count: int
    best_score: int | None
    created_at: datetime
    updated_at: datetime


class VoteResponse(BaseModel):
    password_deleted: bool = False
    access_point_deleted: bool = False
    password_id: uuid.UUID
    up_votes: int
    down_votes: int
    score: int
    my_vote: int | None


class ReportResponse(BaseModel):
    status: str
