from conftest import auth_headers


def _password_id(sample_ap) -> str:
    return sample_ap["ap"]["passwords"][0]["id"]


def test_vote_upsert_retract_flow(client, registered_user, sample_ap):
    voter = registered_user(email="voter@example.com")
    password_id = _password_id(sample_ap)

    up = client.post(
        f"/api/v1/passwords/{password_id}/vote", json={"value": 1}, headers=auth_headers(voter)
    )
    assert up.status_code == 200
    assert up.json()["score"] == 1
    assert up.json()["my_vote"] == 1

    same_again = client.post(
        f"/api/v1/passwords/{password_id}/vote", json={"value": 1}, headers=auth_headers(voter)
    )
    assert same_again.json()["score"] == 1

    switch = client.post(
        f"/api/v1/passwords/{password_id}/vote", json={"value": -1}, headers=auth_headers(voter)
    )
    assert switch.status_code == 200
    assert switch.json()["score"] == -1
    assert switch.json()["my_vote"] == -1

    detail = client.get(
        f"/api/v1/access-points/{sample_ap['ap']['id']}", headers=auth_headers(voter)
    )
    candidate = detail.json()["passwords"][0]
    assert candidate["score"] == -1
    assert candidate["my_vote"] == -1

    retract = client.delete(
        f"/api/v1/passwords/{password_id}/vote", headers=auth_headers(voter)
    )
    assert retract.status_code == 204

    detail = client.get(
        f"/api/v1/access-points/{sample_ap['ap']['id']}", headers=auth_headers(voter)
    )
    candidate = detail.json()["passwords"][0]
    assert candidate["score"] == 0
    assert candidate["my_vote"] is None


def test_vote_counts_recomputed_from_votes_table(client, registered_user, sample_ap, db):
    from app.access_points.models import PasswordVote

    first = registered_user(email="first-voter@example.com")
    second = registered_user(email="second-voter@example.com")
    password_id = _password_id(sample_ap)

    client.post(f"/api/v1/passwords/{password_id}/vote", json={"value": 1}, headers=auth_headers(first))
    client.post(f"/api/v1/passwords/{password_id}/vote", json={"value": 1}, headers=auth_headers(second))
    detail = client.get(
        f"/api/v1/access-points/{sample_ap['ap']['id']}", headers=auth_headers(first)
    )
    assert detail.json()["passwords"][0]["up_votes"] == 2
    assert detail.json()["passwords"][0]["score"] == 2
    assert db.query(PasswordVote).count() == 2

    client.delete(f"/api/v1/passwords/{password_id}/vote", headers=auth_headers(first))
    detail = client.get(
        f"/api/v1/access-points/{sample_ap['ap']['id']}", headers=auth_headers(first)
    )
    assert detail.json()["passwords"][0]["up_votes"] == 1
    assert db.query(PasswordVote).count() == 1


def test_vote_value_validation(client, registered_user, sample_ap):
    voter = registered_user(email="validator@example.com")
    password_id = _password_id(sample_ap)
    assert (
        client.post(f"/api/v1/passwords/{password_id}/vote", json={"value": 0}, headers=auth_headers(voter)).status_code
        == 422
    )
    assert (
        client.post(f"/api/v1/passwords/{password_id}/vote", json={"value": 2}, headers=auth_headers(voter)).status_code
        == 422
    )


def test_vote_on_missing_password(client, registered_user):
    user = registered_user()
    response = client.post(
        "/api/v1/passwords/00000000-0000-0000-0000-000000000000/vote",
        json={"value": 1},
        headers=auth_headers(user),
    )
    assert response.status_code == 404


def test_delete_own_candidate_only(client, registered_user, sample_ap):
    owner = sample_ap["user"]
    other = registered_user(email="not-owner@example.com")
    password_id = _password_id(sample_ap)

    forbidden = client.delete(f"/api/v1/passwords/{password_id}", headers=auth_headers(other))
    assert forbidden.status_code == 403

    allowed = client.delete(f"/api/v1/passwords/{password_id}", headers=auth_headers(owner))
    assert allowed.status_code == 204

    detail = client.get(f"/api/v1/access-points/{sample_ap['ap']['id']}", headers=auth_headers(owner))
    assert detail.json()["passwords"] == []
