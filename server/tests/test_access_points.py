from conftest import auth_headers


def _create(client, user, **overrides):
    payload = {
        "ssid": "Cafe Nero Guest",
        "password": "espresso42",
        "bssid": None,
        "security": "WPA2",
        "latitude": 47.6062,
        "longitude": -122.3321,
        "consent_confirmed": True,
    }
    payload.update(overrides)
    return client.post("/api/v1/access-points", json=payload, headers=auth_headers(user))


def test_create_returns_201_with_first_candidate(client, registered_user):
    user = registered_user()
    response = _create(client, user, bssid="AA:BB:CC:11:22:33")
    assert response.status_code == 201
    body = response.json()
    assert body["ssid"] == "Cafe Nero Guest"
    assert body["bssid"] == "aa:bb:cc:11:22:33"
    assert len(body["passwords"]) == 1
    assert body["passwords"][0]["password"] == "espresso42"
    assert body["passwords"][0]["score"] == 0
    assert body["duplicate_password"] is False


def test_create_requires_consent(client, registered_user):
    user = registered_user()
    response = _create(client, user, consent_confirmed=False)
    assert response.status_code == 422


def test_create_validation_errors(client, registered_user):
    user = registered_user()
    assert _create(client, user, ssid="x" * 33).status_code == 422
    assert _create(client, user, bssid="not-a-mac").status_code == 422
    assert _create(client, user, latitude=91).status_code == 422
    assert _create(client, user, password="").status_code == 422
    assert _create(client, user, password="short").status_code == 422


def test_bssid_match_adds_candidate_without_overwrite(client, registered_user):
    user = registered_user()
    first = _create(client, user, bssid="AA:BB:CC:11:22:33", ssid="Original Name")
    assert first.status_code == 201
    second = _create(client, user, bssid="AA:BB:CC:11:22:33", password="newer-pass", ssid="Different Name")
    assert second.status_code == 200
    body = second.json()
    assert body["ssid"] == "Original Name"
    assert len(body["passwords"]) == 2
    assert {p["password"] for p in body["passwords"]} == {"espresso42", "newer-pass"}


def test_duplicate_password_becomes_upvote(client, registered_user):
    user = registered_user()
    first = _create(client, user, bssid="AA:BB:CC:11:22:33")
    second = _create(client, user, bssid="AA:BB:CC:11:22:33", password="espresso42")
    assert second.status_code == 200
    body = second.json()
    assert body["duplicate_password"] is True
    assert len(body["passwords"]) == 1
    assert body["passwords"][0]["up_votes"] == 1
    assert body["passwords"][0]["score"] == 1
    assert body["passwords"][0]["my_vote"] == 1


def test_add_candidate_endpoint(client, registered_user):
    user = registered_user()
    ap = _create(client, user, bssid="AA:BB:CC:11:22:33").json()
    response = client.post(
        f"/api/v1/access-points/{ap['id']}/passwords",
        json={"password": "second-password", "consent_confirmed": True},
        headers=auth_headers(user),
    )
    assert response.status_code == 201
    assert len(response.json()["passwords"]) == 2
    duplicate = client.post(
        f"/api/v1/access-points/{ap['id']}/passwords",
        json={"password": "second-password", "consent_confirmed": True},
        headers=auth_headers(user),
    )
    assert duplicate.status_code == 200
    assert duplicate.json()["duplicate_password"] is True


def test_near_radius_and_ordering(client, registered_user):
    user = registered_user()
    close = _create(client, user, ssid="Close Net", latitude=47.6071, longitude=-122.3321, password="close-pass")
    mid = _create(client, user, ssid="Mid Net", latitude=47.6006, longitude=-122.3321, password="mid-pass")
    far = _create(client, user, ssid="Far Net", latitude=47.6562, longitude=-122.3321, password="far-pass")
    assert all(r.status_code == 201 for r in (close, mid, far))

    default = client.get(
        "/api/v1/access-points/near",
        params={"lat": 47.6062, "lon": -122.3321},
        headers=auth_headers(user),
    )
    assert default.status_code == 200
    assert [s["ssid"] for s in default.json()] == ["Close Net"]
    assert 50 < default.json()[0]["distance_m"] < 200

    wider = client.get(
        "/api/v1/access-points/near",
        params={"lat": 47.6062, "lon": -122.3321, "radius_m": 1000},
        headers=auth_headers(user),
    )
    assert [s["ssid"] for s in wider.json()] == ["Close Net", "Mid Net"]

    too_far = client.get(
        "/api/v1/access-points/near",
        params={"lat": 47.6062, "lon": -122.3321, "radius_m": 6000},
        headers=auth_headers(user),
    )
    assert too_far.status_code == 422


def test_bbox_clamped_to_two_degrees(client, registered_user):
    user = registered_user()
    south = _create(client, user, ssid="South Net", latitude=47.0, longitude=-122.33)
    north = _create(client, user, ssid="North Net", latitude=49.5, longitude=-122.33)
    assert south.status_code == 201 and north.status_code == 201

    response = client.get(
        "/api/v1/access-points/in-bbox",
        params={"min_lat": 47.0, "min_lon": -122.5, "max_lat": 50.0, "max_lon": -122.1},
        headers=auth_headers(user),
    )
    assert response.status_code == 200
    assert [s["ssid"] for s in response.json()] == ["North Net"]


def test_search_case_insensitive_substring(client, registered_user):
    user = registered_user()
    _create(client, user, ssid="Cafe Nero Guest", password="espresso42")
    _create(client, user, ssid="Cafe Solo", password="password-b")
    _create(client, user, ssid="Hotel FreeWifi", password="password-c")

    cafe = client.get("/api/v1/access-points/search", params={"q": "cafe"}, headers=auth_headers(user))
    assert sorted(s["ssid"] for s in cafe.json()) == ["Cafe Nero Guest", "Cafe Solo"]

    nero = client.get("/api/v1/access-points/search", params={"q": "CAFE NERO"}, headers=auth_headers(user))
    assert [s["ssid"] for s in nero.json()] == ["Cafe Nero Guest"]

    literal = client.get("/api/v1/access-points/search", params={"q": "%cafe"}, headers=auth_headers(user))
    assert literal.json() == []

    long_query = client.get(
        "/api/v1/access-points/search", params={"q": "x" * 65}, headers=auth_headers(user)
    )
    assert long_query.status_code == 422


def test_detail_ranking_and_my_vote(client, registered_user, sample_ap):
    ap_id = sample_ap["ap"]["id"]
    voter = registered_user(email="voter@example.com")
    second_voter = registered_user(email="voter2@example.com")
    password_id = sample_ap["ap"]["passwords"][0]["id"]

    add = client.post(
        f"/api/v1/access-points/{ap_id}/passwords",
        json={"password": "backup-pass", "consent_confirmed": True},
        headers=auth_headers(sample_ap["user"]),
    )
    assert add.status_code == 201
    second_id = next(p["id"] for p in add.json()["passwords"] if p["password"] == "backup-pass")

    upvote = client.post(
        f"/api/v1/passwords/{second_id}/vote", json={"value": 1}, headers=auth_headers(voter)
    )
    assert upvote.status_code == 200
    client.post(f"/api/v1/passwords/{password_id}/vote", json={"value": -1}, headers=auth_headers(voter))

    detail = client.get(f"/api/v1/access-points/{ap_id}", headers=auth_headers(voter))
    assert detail.status_code == 200
    passwords = detail.json()["passwords"]
    assert [p["password"] for p in passwords] == ["backup-pass", "espresso42"]
    assert passwords[0]["score"] == 1
    my_votes = {p["password"]: p["my_vote"] for p in passwords}
    assert my_votes == {"backup-pass": 1, "espresso42": -1}
    assert detail.json()["mine"] is False
    assert all(p["mine"] is False for p in passwords)

    owner_view = client.get(f"/api/v1/access-points/{ap_id}", headers=auth_headers(sample_ap["user"]))
    assert owner_view.status_code == 200
    assert owner_view.json()["mine"] is True
    assert all(p["mine"] for p in owner_view.json()["passwords"])


def test_detail_404(client, registered_user):
    user = registered_user()
    response = client.get(
        "/api/v1/access-points/00000000-0000-0000-0000-000000000000",
        headers=auth_headers(user),
    )
    assert response.status_code == 404


def test_delete_own_access_point_cascades(client, registered_user, db):
    from app.access_points.models import AccessPoint, ApPassword

    user = registered_user()
    created = _create(client, user, bssid="AA:BB:CC:44:55:66").json()
    response = client.delete(f"/api/v1/access-points/{created['id']}", headers=auth_headers(user))
    assert response.status_code == 204
    assert db.get(AccessPoint, created["id"]) is None
    assert db.query(ApPassword).count() == 0


def test_cannot_delete_someone_elses_access_point(client, registered_user, sample_ap):
    other = registered_user(email="stranger@example.com")
    response = client.delete(
        f"/api/v1/access-points/{sample_ap['ap']['id']}", headers=auth_headers(other)
    )
    assert response.status_code == 403


def test_lists_never_expose_passwords_or_contributors(client, registered_user, sample_ap):
    user = registered_user(email="browser@example.com")
    near = client.get(
        "/api/v1/access-points/near",
        params={"lat": 47.6062, "lon": -122.3321},
        headers=auth_headers(user),
    )
    summary = near.json()[0]
    assert "passwords" not in summary
    assert "submitted_by" not in summary
    assert summary["password_count"] == 1
    assert summary["best_score"] == 0
