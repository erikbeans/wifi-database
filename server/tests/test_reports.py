from conftest import auth_headers


def _password_id(sample_ap) -> str:
    return sample_ap["ap"]["passwords"][0]["id"]


def test_three_reports_auto_flag_candidate(client, registered_user, sample_ap, db):
    from app.access_points.models import ApPassword

    password_id = _password_id(sample_ap)
    first = registered_user(email="reporter1@example.com")
    second = registered_user(email="reporter2@example.com")
    third = registered_user(email="reporter3@example.com")

    for i, reporter in enumerate((first, second), start=1):
        response = client.post(
            "/api/v1/reports",
            json={"password_id": password_id, "reason": "wrong"},
            headers=auth_headers(reporter),
        )
        assert response.status_code == 201
        assert db.get(ApPassword, password_id).status == "active"

    final = client.post(
        "/api/v1/reports",
        json={"password_id": password_id, "reason": "wrong"},
        headers=auth_headers(third),
    )
    assert final.status_code == 201
    assert db.get(ApPassword, password_id).status == "flagged"

    detail = client.get(
        f"/api/v1/access-points/{sample_ap['ap']['id']}", headers=auth_headers(first)
    )
    assert detail.status_code == 200
    assert detail.json()["passwords"] == []


def test_ap_level_report_does_not_flag_anything(client, registered_user, sample_ap, db):
    reporter = registered_user(email="ap-reporter@example.com")
    response = client.post(
        "/api/v1/reports",
        json={"ap_id": sample_ap["ap"]["id"], "reason": "abusive"},
        headers=auth_headers(reporter),
    )
    assert response.status_code == 201
    detail = client.get(
        f"/api/v1/access-points/{sample_ap['ap']['id']}", headers=auth_headers(reporter)
    )
    assert len(detail.json()["passwords"]) == 1


def test_report_requires_exactly_one_target(client, registered_user, sample_ap):
    user = registered_user(email="schema-checker@example.com")
    both = client.post(
        "/api/v1/reports",
        json={"ap_id": sample_ap["ap"]["id"], "password_id": _password_id(sample_ap), "reason": "wrong"},
        headers=auth_headers(user),
    )
    assert both.status_code == 422
    neither = client.post(
        "/api/v1/reports",
        json={"reason": "wrong"},
        headers=auth_headers(user),
    )
    assert neither.status_code == 422


def test_report_missing_target_404(client, registered_user):
    user = registered_user()
    response = client.post(
        "/api/v1/reports",
        json={"ap_id": "00000000-0000-0000-0000-000000000000", "reason": "wrong"},
        headers=auth_headers(user),
    )
    assert response.status_code == 404
