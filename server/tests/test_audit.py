from conftest import auth_headers


def test_audit_rows_written_for_sensitive_actions(client, registered_user, sample_ap, db):
    from app.audit import AuditEvent

    ap_id = sample_ap["ap"]["id"]
    password_id = sample_ap["ap"]["passwords"][0]["id"]

    viewer = registered_user(email="viewer@example.com")
    detail = client.get(f"/api/v1/access-points/{ap_id}", headers=auth_headers(viewer))
    assert detail.status_code == 200

    voter = registered_user(email="auditor@example.com")
    vote = client.post(
        f"/api/v1/passwords/{password_id}/vote", json={"value": 1}, headers=auth_headers(voter)
    )
    assert vote.status_code == 200

    reporter = registered_user(email="audit-reporter@example.com")
    report = client.post(
        "/api/v1/reports",
        json={"password_id": password_id, "reason": "wrong"},
        headers=auth_headers(reporter),
    )
    assert report.status_code == 201

    events = db.query(AuditEvent).order_by(AuditEvent.id).all()
    by_type = {}
    for event in events:
        by_type.setdefault(event.event_type, []).append(event)

    assert len(by_type["submission"]) >= 1
    assert str(by_type["submission"][-1].target_id) == ap_id

    views = by_type["password_view"]
    assert len(views) == 1
    assert str(views[0].target_id) == ap_id

    votes = by_type["vote"]
    assert len(votes) == 1
    assert str(votes[0].target_id) == password_id

    reports = by_type["report"]
    assert len(reports) == 1
    assert str(reports[0].target_id) == password_id


def test_list_endpoints_are_not_audited(client, registered_user, sample_ap, db):
    from app.audit import AuditEvent

    user = registered_user(email="lister@example.com")
    client.get(
        "/api/v1/access-points/near",
        params={"lat": 47.6062, "lon": -122.3321},
        headers=auth_headers(user),
    )
    client.get("/api/v1/access-points/search", params={"q": "Cafe"}, headers=auth_headers(user))

    submissions = db.query(AuditEvent).filter(AuditEvent.event_type == "submission").count()
    all_events = db.query(AuditEvent).count()
    assert submissions >= 1
    assert all_events == submissions
